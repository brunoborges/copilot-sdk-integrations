"""Framework-neutral agent layer for the GitHub Copilot SDK for Python."""

from . import policies
from .context import AgentContext, current_invocation
from .definition import AgentDefinition, ConfigIssue
from .errors import (
    CopilotAgentError,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotInvocationError,
    CopilotTimeout,
)
from .hooks import CopilotAgentHooks
from . import message_sources
from .message_sources import current_message_source, message_source
from .invoker import AgentInvoker, CopilotReply, ToolCall
from .loop import LoopIteration, LoopResult, StopReason, arun_loop, run_loop
from .policies import PermissionPolicy
from .recovery import ErrorRecoveryHooks, SdkErrorCategory, ToolResultCategory
from .runtime import CopilotRuntime
from .session_ids import SHARED, derive_session_id
from .sessions import SessionCleanup, SessionMessage, SessionStore
from .tokens import CallerIdentity, CopilotTokenResolver, StaticTokenResolver
from .tools import CopilotTool, ToolContext, ToolError, tool

__all__ = [
    "SHARED",
    "AgentContext",
    "AgentDefinition",
    "AgentInvoker",
    "CallerIdentity",
    "ConfigIssue",
    "CopilotAgentError",
    "CopilotAgentHooks",
    "CopilotConfigurationError",
    "CopilotConversationNotFound",
    "CopilotInvocationError",
    "CopilotReply",
    "CopilotRuntime",
    "CopilotTimeout",
    "CopilotTokenResolver",
    "CopilotTool",
    "ErrorRecoveryHooks",
    "LoopIteration",
    "LoopResult",
    "PermissionPolicy",
    "SdkErrorCategory",
    "SessionCleanup",
    "SessionMessage",
    "SessionStore",
    "StaticTokenResolver",
    "StopReason",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "ToolResultCategory",
    "arun_loop",
    "current_invocation",
    "current_message_source",
    "derive_session_id",
    "message_source",
    "message_sources",
    "policies",
    "run_loop",
    "tool",
]
