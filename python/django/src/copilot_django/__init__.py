"""Django integration for the GitHub Copilot SDK."""

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

from .agents import Conversation, CopilotAgent, prompt, register
from .tools import copilot_tool

__all__ = [
    "SHARED",
    "AgentContext",
    "Conversation",
    "CopilotAgent",
    "CopilotAgentError",
    "CopilotAgentHooks",
    "CopilotConfigurationError",
    "CopilotConversationNotFound",
    "CopilotInvocationError",
    "CopilotReply",
    "CopilotTimeout",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "copilot_tool",
    "current_invocation",
    "message_source",
    "policies",
    "prompt",
    "register",
]
