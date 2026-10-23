package com.microsoft.copilot.integrations.quarkus.runtime.security;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotTokenResolver;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotRuntimeConfig;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Uses the bearer access token of the current Quarkus {@link SecurityIdentity} (for example the GitHub access
 * token obtained by {@code quarkus-oidc} with the GitHub provider) as the Copilot session token.
 *
 * <p>The identity is read on the invoking thread. Sessions are partitioned by principal name, so users never resume
 * each other's conversations. Without an authenticated identity or access token, the invocation fails unless
 * {@code quarkus.copilot.security.identity.ambient-fallback} is enabled, in which case the client's ambient
 * credentials are used and unauthenticated callers share one unpartitioned space.
 */
@Singleton
public class IdentityCopilotTokenResolver implements CopilotTokenResolver {

    private final Instance<SecurityIdentity> identity;
    private final boolean ambientFallback;

    @Inject
    public IdentityCopilotTokenResolver(Instance<SecurityIdentity> identity, CopilotRuntimeConfig config) {
        this(identity, config.security().identity().ambientFallback());
    }

    public IdentityCopilotTokenResolver(Instance<SecurityIdentity> identity, boolean ambientFallback) {
        this.identity = identity;
        this.ambientFallback = ambientFallback;
    }

    @Override
    public String partitionKey(String agentName, String conversationId) {
        SecurityIdentity current = authenticated();
        if (current == null) {
            return ambientFallback ? SHARED : missing("no authenticated user");
        }
        return "identity:" + current.getPrincipal().getName();
    }

    @Override
    public String resolveToken(CopilotAgentContext context) {
        SecurityIdentity current = authenticated();
        if (current == null) {
            return missing("no authenticated user");
        }
        for (var credential : current.getCredentials()) {
            if (credential instanceof TokenCredential token && "bearer".equalsIgnoreCase(token.getType())
                    && token.getToken() != null && !token.getToken().isBlank()) {
                return token.getToken();
            }
        }
        return missing("the current identity has no bearer access token");
    }

    private SecurityIdentity authenticated() {
        try {
            if (!identity.isResolvable()) {
                return null;
            }
            SecurityIdentity current = identity.get();
            return current == null || current.isAnonymous() ? null : current;
        } catch (ContextNotActiveException ex) {
            return null;
        }
    }

    private String missing(String reason) {
        if (ambientFallback) {
            return null;
        }
        throw new IllegalStateException("Cannot resolve a Copilot token from the security identity: " + reason);
    }

    @Override
    public String toString() {
        return "IdentityCopilotTokenResolver[ambientFallback=" + ambientFallback + "]";
    }
}
