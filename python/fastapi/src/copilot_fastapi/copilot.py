"""The ``Copilot`` extension object: runtime, agent registry, DI, lifespan, and error handlers."""

from __future__ import annotations

import asyncio
import inspect
import logging
import os
import threading
from collections.abc import AsyncIterator, Callable, Iterable, Iterator, Mapping
from contextlib import asynccontextmanager, contextmanager
from typing import Any, TypeVar

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from copilot_agents import (
    AgentDefinition,
    AgentInvoker,
    CallerIdentity,
    ConfigIssue,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotInvocationError,
    CopilotRuntime,
    CopilotTimeout,
)
from copilot_agents.executor import ThreadPoolDispatcher
from copilot_agents.policies import approves_everything
from copilot_agents.testing import CopilotTestRuntime

from .agents import CopilotAgent, build_definition
from .auth import UserKey, default_user_key
from .settings import CopilotSettings

log = logging.getLogger("copilot_fastapi")

A = TypeVar("A", bound=CopilotAgent)

SECRET_KEYS = ("token", "key", "secret", "password", "authorization")


def redact(value: Any) -> Any:
    """Mask secret-looking keys in nested configuration before it is logged or returned."""
    if isinstance(value, Mapping):
        return {
            k: ("***" if any(s in str(k).lower() for s in SECRET_KEYS) and v else redact(v)) for k, v in value.items()
        }
    if isinstance(value, list | tuple):
        return [redact(v) for v in value]
    return value


async def _sync_runner(fn: Callable[[], Any]) -> Any:
    return await run_in_threadpool(fn)


class Copilot:
    """Connects a FastAPI application to the Copilot runtime.

    ::

        copilot = Copilot(current_user=get_current_user)
        app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)

        @app.post("/support")
        async def ask(q: Question, agent: Annotated[SupportAgent, copilot.depends(SupportAgent)]):
            return await agent.aask(q.text)

    One instance owns one runtime (one Copilot client per worker process). ``current_user``
    is a FastAPI dependency whose result becomes the caller identity; ``user_key`` maps it
    to the key that partitions conversations between users.
    """

    def __init__(
        self,
        settings: CopilotSettings | None = None,
        *,
        agents: Iterable[type[CopilotAgent]] = (),
        current_user: Callable[..., Any] | None = None,
        user_key: UserKey = default_user_key,
        token_resolver: Any = None,
        hooks: Iterable[Any] = (),
        expose_tool_errors: bool = False,
        client_factory: Callable[[], Any] | None = None,
    ) -> None:
        self.settings = settings if settings is not None else CopilotSettings()
        self.current_user = current_user
        self.user_key = user_key
        self.token_resolver = token_resolver
        self.hooks = list(hooks)
        self.expose_tool_errors = expose_tool_errors
        self.runtime = CopilotRuntime(client_factory or self.build_client, name="copilot-fastapi")
        self.fallback = ThreadPoolDispatcher()
        self._agents: dict[str, type[CopilotAgent]] = {}
        self._definitions: dict[type[CopilotAgent], AgentDefinition] = {}
        self._invokers: dict[type[CopilotAgent], AgentInvoker] = {}
        self._dependencies: dict[type[CopilotAgent], Any] = {}
        self._lock = threading.RLock()
        for cls in agents:
            self.register(cls)

    def __repr__(self) -> str:
        return f"<Copilot agents={sorted(self._agents)} runtime={self.runtime.status()}>"

    # -- runtime ----------------------------------------------------------------------

    def build_client(self) -> Any:
        """Create an SDK ``CopilotClient`` from settings (not started)."""
        from copilot import CopilotClient, RuntimeConnection

        runtime = self.settings.runtime
        token = runtime.connection_token.get_secret_value() if runtime.connection_token else None
        if runtime.connection == "uri":
            connection = RuntimeConnection.for_uri(runtime.url, connection_token=token)
        elif runtime.connection == "tcp":
            connection = RuntimeConnection.for_tcp(port=runtime.port, connection_token=token, path=runtime.cli_path)
        elif runtime.connection == "inprocess":
            connection = RuntimeConnection.for_inprocess()
        else:
            connection = RuntimeConnection.for_stdio(path=runtime.cli_path)
        base = self.settings.base_directory
        base.mkdir(parents=True, exist_ok=True)
        kwargs: dict[str, Any] = {
            "connection": connection,
            "base_directory": str(base),
            "log_level": runtime.log_level,
            "mode": runtime.mode,
        }
        if self.settings.auth.github_token:
            kwargs["github_token"] = self.settings.auth.github_token.get_secret_value()
        if self.settings.telemetry:
            kwargs["telemetry"] = {str(k).lower(): v for k, v in self.settings.telemetry.items()}
        return CopilotClient(**kwargs)

    # -- agents -----------------------------------------------------------------------

    def register(self, cls: type[A]) -> type[A]:
        """Register an agent class (also usable as a class decorator)."""
        if not (inspect.isclass(cls) and issubclass(cls, CopilotAgent)) or cls.abstract:
            raise CopilotConfigurationError(f"{cls!r} is not a concrete CopilotAgent subclass")
        with self._lock:
            existing = self._agents.get(cls.name)
            if existing is not None and existing is not cls:
                issue = ConfigIssue(
                    "duplicate",
                    f"Copilot agent name {cls.name!r} is used by {existing.__qualname__} and {cls.__qualname__}",
                )
                raise CopilotConfigurationError(issue.message, [issue])
            self._agents[cls.name] = cls
        return cls

    agent = register

    @property
    def agents(self) -> Mapping[str, type[CopilotAgent]]:
        return dict(self._agents)

    def definition(self, cls: type[CopilotAgent]) -> AgentDefinition:
        """The resolved definition for ``cls`` (built once, then cached)."""
        definition = self._definitions.get(cls)
        if definition is None:
            with self._lock:
                definition = self._definitions.get(cls)
                if definition is None:
                    self.register(cls)
                    definition = build_definition(cls, self)
                    self._definitions[cls] = definition
        return definition

    def invoker(self, cls: type[CopilotAgent]) -> AgentInvoker:
        invoker = self._invokers.get(cls)
        if invoker is None:
            definition = self.definition(cls)
            with self._lock:
                invoker = self._invokers.get(cls)
                if invoker is None:
                    invoker = AgentInvoker(
                        definition,
                        self.runtime,
                        sync_runner=_sync_runner,
                        fallback=self.fallback,
                        partition_fallback=self._partition,
                    )
                    self._invokers[cls] = invoker
        return invoker

    def reset(self) -> None:
        """Forget cached definitions so the next call rebuilds them from current settings."""
        with self._lock:
            self._definitions.clear()
            self._invokers.clear()

    def _partition(self, identity: CallerIdentity) -> str | None:
        if identity.user is None:
            return None
        key = self.user_key(identity.user)
        if key is None:
            raise CopilotConfigurationError(
                f"Copilot agent {identity.agent_name!r}: cannot derive a partition key from user "
                f"{type(identity.user).__name__}; pass Copilot(user_key=...) so conversations are not shared"
            )
        return key

    def get(
        self,
        cls: type[A],
        *,
        user: Any = None,
        request: Any = None,
        attributes: Mapping[str, Any] | None = None,
    ) -> A:
        """An agent handle outside a request (scripts, background tasks, workers)."""
        self.register(cls)
        return cls(self, user=user, request=request, attributes=attributes)

    def depends(self, cls: type[A], /, **dependencies: Any) -> Any:
        """A FastAPI ``Depends`` that yields ``cls`` bound to the current user and request.

        Extra keyword arguments are dependencies (``Depends(get_db)``) whose values become
        ``attributes``, so tools can reach them via ``ctx.agent.attributes["db"]``.
        """
        self.register(cls)
        if not dependencies and cls in self._dependencies:
            return self._dependencies[cls]

        reserved = {"request", "user"} & set(dependencies)
        if reserved:
            raise ValueError(f"copilot.depends(): {sorted(reserved)} are reserved names")

        def provide(request: Request, **values: Any) -> A:
            user = values.pop("user", None)
            return cls(self, user=user, request=request, attributes=values)

        params = [inspect.Parameter("request", inspect.Parameter.POSITIONAL_OR_KEYWORD, annotation=Request)]
        if self.current_user is not None:
            params.append(
                inspect.Parameter("user", inspect.Parameter.KEYWORD_ONLY, default=Depends(self.current_user))
            )
        for name, dependency in dependencies.items():
            params.append(inspect.Parameter(name, inspect.Parameter.KEYWORD_ONLY, default=dependency))
        provide.__signature__ = inspect.Signature(params, return_annotation=cls)  # type: ignore[attr-defined]
        provide.__name__ = f"provide_{cls.name}"
        result = Depends(provide)
        if not dependencies:
            self._dependencies[cls] = result
        return result

    # -- validation -------------------------------------------------------------------

    def check(self) -> list[str]:
        """Build every registered agent's definition without starting the runtime.

        Raises :class:`CopilotConfigurationError` listing every problem; returns warnings.
        """
        issues: list[ConfigIssue] = []
        warnings: list[str] = []
        for name, cls in sorted(self.agents.items()):
            try:
                definition = self.definition(cls)
            except CopilotConfigurationError as exc:
                issues.extend(exc.issues or [ConfigIssue("invalid", str(exc))])
                continue
            if approves_everything(definition.permissions):
                warnings.append(f"Copilot agent {name!r} approves every permission request (allow_all).")
        unknown = sorted(set(self.settings.agents) - set(self._agents))
        if unknown and self._agents:
            warnings.append(f"settings.agents configures unregistered agents: {', '.join(unknown)}")
        if issues:
            raise CopilotConfigurationError("; ".join(i.message for i in issues), issues)
        return warnings + self._runtime_warnings()

    def _runtime_warnings(self) -> list[str]:
        runtime = self.settings.runtime
        warnings: list[str] = []
        if runtime.connection in ("stdio", "tcp") and not runtime.cli_path and not os.environ.get("COPILOT_CLI_PATH"):
            try:
                from copilot._cli_download import get_cached_cli_path

                missing = get_cached_cli_path() is None
            except Exception:  # noqa: BLE001 - SDK internals may change; skip the check
                missing = False
            if missing:
                warnings.append(
                    "The Copilot runtime has not been downloaded; the first agent call will download it. "
                    "Run `python -m copilot download-runtime` at build time."
                )
        if runtime.connection == "stdio":
            try:
                workers = int(os.environ.get("WEB_CONCURRENCY", "0"))
            except ValueError:
                workers = 0
            if workers > 2:
                warnings.append(
                    f"WEB_CONCURRENCY={workers} with connection='stdio' starts one Copilot runtime per worker; "
                    "consider a shared runtime with COPILOT_RUNTIME__CONNECTION=uri."
                )
        return warnings

    # -- application wiring -----------------------------------------------------------

    @asynccontextmanager
    async def lifespan(self, app: FastAPI | None = None) -> AsyncIterator[None]:
        """Validate agents, optionally start the runtime, and shut it down on exit."""
        if self.settings.validate_on_startup:
            for warning in self.check():
                log.warning(warning)
        if self.settings.runtime.start == "eager":
            await self.runtime.run_async(self.runtime.client())
        try:
            yield
        finally:
            await asyncio.to_thread(self.runtime.shutdown)

    def init_app(self, app: FastAPI) -> None:
        """Wire into an app that already has its own lifespan (runs around it)."""
        inner = app.router.lifespan_context

        @asynccontextmanager
        async def lifespan(application: Any) -> AsyncIterator[Any]:
            async with self.lifespan(application), inner(application) as state:
                yield state

        app.router.lifespan_context = lifespan
        self.install_exception_handlers(app)

    @property
    def exception_handlers(self) -> dict[type[Exception], Callable[..., Any]]:
        """Handlers mapping Copilot errors to 404, 504, 502, and 500 responses.

        Pass them to ``FastAPI(exception_handlers=...)``, or call :meth:`init_app`. Details
        are generic so runtime and configuration internals never reach clients.
        """
        statuses: dict[type[Exception], tuple[int, str]] = {
            CopilotConversationNotFound: (404, "Conversation not found."),
            CopilotTimeout: (504, "The Copilot agent did not answer in time."),
            CopilotInvocationError: (502, "The Copilot agent failed to answer."),
            CopilotConfigurationError: (500, "The Copilot agent is misconfigured."),
        }
        handlers: dict[type[Exception], Callable[..., Any]] = {}
        for exc_type, (status, detail) in statuses.items():

            async def handle(request: Request, exc: Exception, status: int = status, detail: str = detail) -> JSONResponse:
                if status >= 500:
                    log.error("Copilot request failed: %s", exc, exc_info=exc)
                return JSONResponse({"detail": detail}, status_code=status)

            handlers[exc_type] = handle
        return handlers

    def install_exception_handlers(self, app: FastAPI) -> None:
        """Add :attr:`exception_handlers` to ``app`` before it starts; existing handlers win."""
        for exc_type, handler in self.exception_handlers.items():
            if exc_type not in app.exception_handlers:
                app.add_exception_handler(exc_type, handler)

    async def health(self, *, deep: bool = False, timeout: float = 30.0) -> dict[str, Any]:
        """Runtime status and registered agents; ``deep`` starts the runtime and checks auth."""
        report: dict[str, Any] = {
            "connection": self.settings.runtime.connection,
            "runtime": self.runtime.status(),
            "agents": sorted(self._agents),
        }
        if deep:

            async def probe(client: Any) -> Any:
                return await client.get_auth_status()

            async def call() -> Any:
                return await probe(await self.runtime.client())

            try:
                status = await asyncio.wait_for(self.runtime.run_async(call()), timeout)
            except Exception as exc:  # noqa: BLE001 - reported to the caller
                report["runtime"] = self.runtime.status()
                report["error"] = f"{type(exc).__name__}: {exc}"
                report["ok"] = False
                return report
            report["runtime"] = self.runtime.status()
            report["authenticated"] = bool(getattr(status, "is_authenticated", False))
        report["ok"] = not report["runtime"].startswith("failed") and report.get("authenticated", True)
        return report

    def router(self, *, prefix: str = "/copilot", tags: list[str] | None = None, **kwargs: Any) -> APIRouter:
        """An ``APIRouter`` with ``GET {prefix}/health`` (``?deep=true`` checks auth)."""
        router = APIRouter(prefix=prefix, tags=tags or ["copilot"], **kwargs)

        @router.get("/health")
        async def copilot_health(deep: bool = False) -> JSONResponse:
            report = await self.health(deep=deep)
            return JSONResponse(report, status_code=200 if report["ok"] else 503)

        return router

    # -- testing ----------------------------------------------------------------------

    @contextmanager
    def test_runtime(self, **kwargs: Any) -> Iterator[CopilotTestRuntime]:
        """Swap in a scripted in-memory runtime (see :mod:`copilot_fastapi.testing`)."""
        fake = CopilotTestRuntime(**kwargs)
        with fake.install(self.runtime):
            yield fake
