"""FastAPI integration for the GitHub Copilot SDK."""

from copilot_agents import (
    SHARED,
    AgentContext,
    CopilotAgentError,
    CopilotAgentHooks,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotInvocationError,
    CopilotReply,
    CopilotTimeout,
    SessionCleanup,
    SessionMessage,
    ToolCall,
    ToolContext,
    ToolError,
    current_invocation,
    policies,
)

from .agents import Conversation, CopilotAgent, PromptRenderError, prompt
from .auth import UserTokenResolver
from .copilot import Copilot, CopilotSessionStore
from .settings import CopilotSettings
from .tools import copilot_tool

__all__ = [
    "SHARED",
    "AgentContext",
    "Conversation",
    "Copilot",
    "CopilotAgent",
    "CopilotAgentError",
    "CopilotAgentHooks",
    "CopilotConfigurationError",
    "CopilotConversationNotFound",
    "CopilotInvocationError",
    "CopilotReply",
    "CopilotSessionStore",
    "CopilotSettings",
    "CopilotTimeout",
    "PromptRenderError",
    "SessionCleanup",
    "SessionMessage",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "UserTokenResolver",
    "copilot_tool",
    "current_invocation",
    "policies",
    "prompt",
]
