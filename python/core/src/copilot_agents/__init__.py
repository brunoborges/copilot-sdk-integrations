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
from .policies import PermissionPolicy
from .runtime import CopilotRuntime
from .session_ids import SHARED, derive_session_id
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
    "PermissionPolicy",
    "StaticTokenResolver",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "current_invocation",
    "current_message_source",
    "derive_session_id",
    "message_source",
    "message_sources",
    "policies",
    "tool",
]
