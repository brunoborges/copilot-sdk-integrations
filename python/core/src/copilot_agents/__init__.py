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
from .invoker import AgentInvoker, CopilotReply, ToolCall
from .policies import PermissionPolicy
from .recovery import ErrorRecoveryHooks, SdkErrorCategory, ToolResultCategory
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
    "ErrorRecoveryHooks",
    "PermissionPolicy",
    "SdkErrorCategory",
    "StaticTokenResolver",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "ToolResultCategory",
    "current_invocation",
    "derive_session_id",
    "policies",
    "tool",
]
