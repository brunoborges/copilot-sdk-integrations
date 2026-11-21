"""Build Microsoft Copilot SDK runtime connections and clients from integration settings.

Integrations describe the runtime with a connection kind (``stdio``, ``tcp``,
``inprocess``, or ``uri``) plus a few plain settings. These helpers translate them into
the SDK's ``RuntimeConnection`` factories and ``CopilotClient`` option groups:

* ``stdio`` spawns a runtime child process over stdio (``for_spawned_stdio``).
* ``tcp`` spawns a runtime child process listening on TCP (``for_spawned_tcp``).
* ``inprocess`` hosts the runtime in this process (``for_inprocess``).
* ``uri`` attaches to an already-running runtime (``for_tcp(host, port)``). Runtime
  startup settings (base directory, log level, token, telemetry) belong to whoever runs
  that runtime and are not sent.
"""

from __future__ import annotations

import ipaddress
import os
import re
import sys
from collections.abc import Mapping
from typing import Any

CONNECTIONS = ("stdio", "uri", "tcp", "inprocess")
MODES = ("coding-agent", "empty")
LEGACY_MODES = {"copilot-cli": "coding-agent"}
"""Mode names from earlier SDK versions and their current equivalents."""


def client_mode(mode: str) -> str:
    """The SDK client mode for ``mode``, accepting legacy names such as ``copilot-cli``."""
    resolved = LEGACY_MODES.get(mode, mode)
    if resolved not in MODES:
        raise ValueError(f"Copilot client mode must be one of {', '.join(MODES)} (got {mode!r})")
    return resolved


def parse_runtime_url(url: str) -> tuple[str, int]:
    """Split a runtime URL into ``(host, port)``.

    Accepts ``port``, ``host:port``, ``[ipv6]:port``, and ``http(s)://host:port``; an
    empty host means ``localhost``.
    """
    text = re.sub(r"^https?://", "", str(url).strip()).rstrip("/")
    if text.isdigit():
        host, port_text = "localhost", text
    elif match := re.match(r"^\[([^\]]+)\]:(.*)$", text):
        host, port_text = match.group(1), match.group(2)
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            raise ValueError(f"Invalid Copilot runtime URL: {url!r}") from None
    else:
        parts = text.split(":")
        if len(parts) != 2:
            raise ValueError(f"Invalid Copilot runtime URL: {url!r}; expected host:port")
        host, port_text = parts[0] or "localhost", parts[1]
    if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
        raise ValueError(f"Invalid port in Copilot runtime URL: {url!r}")
    return host, int(port_text)


def runtime_connection(
    kind: str,
    *,
    url: str | None = None,
    connection_token: str | None = None,
    cli_path: str | None = None,
    port: int | None = None,
    base_directory: str | None = None,
) -> Any:
    """The SDK ``RuntimeConnection`` for a connection kind (see the module docstring)."""
    from copilot import RuntimeConnection

    connection_token = connection_token or None
    if kind == "uri":
        if not url:
            raise ValueError("A runtime URL is required for the 'uri' connection")
        host, url_port = parse_runtime_url(url)
        return RuntimeConnection.for_tcp(host, url_port, connection_token=connection_token)
    if kind == "tcp":
        return RuntimeConnection.for_spawned_tcp(
            port=port or 0,
            connection_token=connection_token,
            path=cli_path,
            base_directory=base_directory,
        )
    if kind == "inprocess":
        return RuntimeConnection.for_inprocess(base_directory=base_directory)
    if kind == "stdio":
        return RuntimeConnection.for_spawned_stdio(path=cli_path, base_directory=base_directory)
    raise ValueError(f"Copilot runtime connection must be one of {', '.join(CONNECTIONS)} (got {kind!r})")


def build_client(
    kind: str,
    *,
    mode: str,
    url: str | None = None,
    connection_token: str | None = None,
    cli_path: str | None = None,
    port: int | None = None,
    base_directory: str | None = None,
    log_level: str | None = None,
    github_token: str | None = None,
    telemetry: Mapping[str, Any] | None = None,
) -> Any:
    """Create an unstarted SDK ``CopilotClient``.

    For ``uri`` only the connection and mode are used; the other settings configure a
    runtime this client owns.
    """
    from copilot import CopilotClient

    kwargs: dict[str, Any] = {"mode": client_mode(mode)}
    if kind == "uri":
        kwargs["runtime"] = runtime_connection(kind, url=url, connection_token=connection_token)
        return CopilotClient(**kwargs)
    kwargs["runtime"] = runtime_connection(
        kind,
        connection_token=connection_token,
        cli_path=cli_path,
        port=port,
        base_directory=base_directory,
    )
    observability: dict[str, Any] = {}
    if log_level:
        observability["log_level"] = log_level
    if telemetry:
        observability["telemetry"] = dict(telemetry)
    if observability:
        kwargs["observability"] = observability
    if github_token:
        kwargs["github"] = {"token": github_token}
    return CopilotClient(**kwargs)


def runtime_downloaded() -> bool | None:
    """Whether the SDK's pinned runtime bundle is in the local cache.

    ``None`` when unknown (for example a development install of the SDK with no pinned
    runtime version). Owned connections without an explicit CLI path download the
    bundle on first use; ``python -m copilot download-runtime`` provisions it ahead.
    """
    try:
        from copilot import _cli_download as download

        version = download.CLI_VERSION
        if not version:
            return None
        pair_dir = download.get_cache_dir(version) / "prebuilds" / download.get_runtime_platform()
        wrapper = "copilot-runtime.exe" if sys.platform == "win32" else "copilot-runtime"
        return bool(download._runtime_bundle_is_complete(pair_dir, wrapper))
    except Exception:  # noqa: BLE001 - SDK internals may change; report unknown
        return None


def runtime_download_pending(kind: str, cli_path: str | None = None) -> bool:
    """Whether connection ``kind`` will download the runtime on its first use.

    ``uri`` never does; ``COPILOT_CLI_PATH`` or, except for ``inprocess``, an explicit CLI
    path also avoids the download. Unknown cache state counts as not pending.
    """
    if kind == "uri" or os.environ.get("COPILOT_CLI_PATH"):
        return False
    if cli_path and kind != "inprocess":
        return False
    return runtime_downloaded() is False
