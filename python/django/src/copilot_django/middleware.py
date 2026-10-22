"""Middleware that makes the current request available to agents and tools."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from asgiref.sync import iscoroutinefunction
from django.utils.decorators import sync_and_async_middleware

from .context import _request


@sync_and_async_middleware
def CopilotContextMiddleware(get_response: Callable[[Any], Any]) -> Callable[[Any], Any]:  # noqa: N802
    """Stores the request in a context variable; the user is resolved lazily when needed."""
    if iscoroutinefunction(get_response):

        async def middleware(request: Any) -> Any:
            token = _request.set(request)
            try:
                return await get_response(request)
            finally:
                _request.reset(token)

    else:

        def middleware(request: Any) -> Any:
            token = _request.set(request)
            try:
                return get_response(request)
            finally:
                _request.reset(token)

    return middleware
