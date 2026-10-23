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
    ToolCall,
    ToolContext,
    ToolError,
    current_invocation,
    message_source,
    policies,
)

from .agents import Conversation, CopilotAgent, PromptRenderError, prompt
from .auth import UserTokenResolver
from .copilot import Copilot
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
    "CopilotSettings",
    "CopilotTimeout",
    "PromptRenderError",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "UserTokenResolver",
    "copilot_tool",
    "current_invocation",
    "message_source",
    "policies",
    "prompt",
]
