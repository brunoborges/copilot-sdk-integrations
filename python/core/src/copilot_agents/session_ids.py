"""Derivation of runtime session IDs from agent identity and conversation IDs.

Matches ``CopilotAgentInvoker.sessionId`` in ``java-core`` so the same agent type, name,
conversation, and partition map to the same session ID in both languages.
"""

from __future__ import annotations

import hashlib

SHARED = ""
"""Partition key for sessions shared by every caller (ambient or static identity)."""


def derive_session_id(
    type_name: str, agent_name: str, conversation_id: str, partition_key: str | None = SHARED
) -> str:
    identity = f"{type_name}\0{agent_name}\0{conversation_id}"
    if partition_key:
        identity += f"\0{partition_key}"
    return "agent-" + hashlib.sha256(identity.encode("utf-8")).hexdigest()
