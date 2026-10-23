package io.github.brunoborges.copilot.micronaut.security;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotTokenResolver;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.annotation.Requires;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;

/**
 * Uses a GitHub access token carried by the current Micronaut Security {@link Authentication} as the Copilot session
 * token. The token is read from the authentication attribute named by
 * {@code micronaut.copilot.security.identity.token-attribute} (default {@code accessToken}); populate it, for example,
 * from the GitHub OAuth 2.0 token response in an {@code OauthAuthenticationMapper}.
 *
 * <p>Sessions are partitioned by user name, so users never resume each other's conversations. Without an
 * authenticated user or token, the invocation fails unless {@code micronaut.copilot.security.identity.ambient-fallback}
 * is enabled, in which case the client's ambient credentials are used and unauthenticated callers share one
 * unpartitioned space.
 */
@Singleton
@Requires(classes = SecurityService.class)
@Requires(property = CopilotRuntimeConfig.PREFIX + ".security.identity-token-resolver.enabled", value = "true")
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public class SecurityCopilotTokenResolver implements CopilotTokenResolver {

    public static final String DEFAULT_TOKEN_ATTRIBUTE = "accessToken";

    private final SecurityService security;
    private final String tokenAttribute;
    private final boolean ambientFallback;

    @Inject
    public SecurityCopilotTokenResolver(SecurityService security, CopilotRuntimeConfig config) {
        this(security, config.security().identity().tokenAttribute(), config.security().identity().ambientFallback());
    }

    public SecurityCopilotTokenResolver(SecurityService security, String tokenAttribute, boolean ambientFallback) {
        this.security = security;
        this.tokenAttribute = tokenAttribute;
        this.ambientFallback = ambientFallback;
    }

    @Override
    public String partitionKey(String agentName, String conversationId) {
        Authentication current = authenticated().orElse(null);
        if (current == null) {
            return ambientFallback ? SHARED : missing("no authenticated user");
        }
        return "identity:" + current.getName();
    }

    @Override
    public String resolveToken(CopilotAgentContext context) {
        Authentication current = authenticated().orElse(null);
        if (current == null) {
            return missing("no authenticated user");
        }
        Object token = current.getAttributes().get(tokenAttribute);
        if (token instanceof CharSequence text && !text.toString().isBlank()) {
            return text.toString();
        }
        return missing("the current authentication has no '" + tokenAttribute + "' attribute");
    }

    private Optional<Authentication> authenticated() {
        try {
            return security.getAuthentication();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String missing(String reason) {
        if (ambientFallback) {
            return null;
        }
        throw new IllegalStateException("Cannot resolve a Copilot token from the security context: " + reason);
    }

    @Override
    public String toString() {
        return "SecurityCopilotTokenResolver[tokenAttribute=" + tokenAttribute + ", ambientFallback="
                + ambientFallback + "]";
    }
}
