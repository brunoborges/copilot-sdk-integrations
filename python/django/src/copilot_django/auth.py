"""Token resolvers for Django projects."""

from __future__ import annotations

from copilot_agents import SHARED, CallerIdentity, CopilotTokenResolver, StaticTokenResolver

__all__ = ["SHARED", "CallerIdentity", "CopilotTokenResolver", "StaticTokenResolver", "UserTokenResolver"]


class UserTokenResolver:
    """Base class for per-user tokens; sessions are partitioned by the user's primary key.

    Subclasses implement :meth:`token_for_user`. Anonymous callers get the ambient identity.
    """

    def resolve(self, identity: CallerIdentity) -> str | None:
        user = identity.user
        if user is None or not getattr(user, "is_authenticated", False):
            return None
        return self.token_for_user(user)

    def partition_key(self, identity: CallerIdentity) -> str | None:
        user = identity.user
        if user is None or not getattr(user, "is_authenticated", False):
            return SHARED
        return str(user.pk)

    def token_for_user(self, user: object) -> str | None:  # pragma: no cover - abstract
        raise NotImplementedError
