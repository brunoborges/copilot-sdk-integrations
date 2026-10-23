---
title: Startup, errors and health
---

# Startup, errors and health

## Lifespan

`copilot.lifespan` is a FastAPI lifespan. On startup it validates every registered agent
(unless `COPILOT_VALIDATE_ON_STARTUP=false`) and, with `COPILOT_RUNTIME__START=eager`, starts the
runtime. When `COPILOT_SESSIONS__MAX_AGE` is set it also starts the
[session cleanup](configuration.md#session-cleanup) task. On shutdown it cancels that task and
stops the runtime.

```python
app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
```

If the app already has a lifespan, use `init_app` instead. It runs the Copilot lifespan around
yours and installs the exception handlers:

```python
app = FastAPI(lifespan=my_lifespan)
copilot.init_app(app)
```

## Validation

`copilot.check()` builds every agent definition without starting the runtime. It raises one
`CopilotConfigurationError` listing every problem, and returns a list of warnings. Call it in
CI or a smoke test to catch mistakes before deployment.

| Problem | Result |
|---|---|
| Unknown tool, MCP server, provider, hook, or token resolver | Error |
| Invalid tool filters or settings overrides | Error |
| An agent uses `allow_all()` | Warning |
| `settings.agents` configures an agent that isn't registered | Warning |
| The runtime hasn't been downloaded, so the first call will download it | Warning |
| `WEB_CONCURRENCY` above 2 with `connection=stdio` | Warning |

The lifespan logs warnings with the `copilot_fastapi` logger.

## Error mapping

`copilot.exception_handlers` maps Copilot errors to HTTP responses:

| Exception | Status | Body |
|---|---|---|
| `CopilotConversationNotFound` | 404 | `{"detail": "Conversation not found."}` |
| `CopilotTimeout` | 504 | `{"detail": "The Copilot agent did not answer in time."}` |
| `CopilotInvocationError` | 502 | `{"detail": "The Copilot agent failed to answer."}` |
| `CopilotConfigurationError` | 500 | `{"detail": "The Copilot agent is misconfigured."}` |

Bodies are generic so runtime details never reach clients; 5xx errors are logged with their
traceback. With `init_app`, handlers you registered yourself take precedence.

## Health

`copilot.router()` returns an `APIRouter` with `GET /copilot/health`:

```python
app.include_router(copilot.router())                 # or router(prefix="/internal/copilot")
```

```json
{"connection": "stdio", "runtime": "ready", "agents": ["support"], "ok": true}
```

`?deep=true` starts the runtime if needed and checks authentication, adding `authenticated`.
The `runtime` field is `stopped`, `starting`, `ready`, or `failed(<reason>)`.
The route returns 503 when `ok` is false. The same report is available as
`await copilot.health(deep=True)`.

## Deployment notes

- **Download the runtime at build time** with `python -m copilot download-runtime`, so the first
  request doesn't download it.
- **Many workers**: each worker with `connection=stdio` runs its own runtime process. For larger
  deployments, run one runtime as a sidecar and use `COPILOT_RUNTIME__CONNECTION=uri`.
- **Logging**: loggers are under `copilot_agents` (for example `copilot_agents.tools`) and
  `copilot_fastapi`. Denied permission requests are logged at `INFO`.
