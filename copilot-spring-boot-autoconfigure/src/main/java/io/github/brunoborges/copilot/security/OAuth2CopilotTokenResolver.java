package io.github.brunoborges.copilot.security;

import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotTokenResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.util.Assert;

/**
 * Uses the current user's Spring Security OAuth2 authorized client (for example a GitHub OAuth App or GitHub App
 * login) as the Copilot session token.
 *
 * <p>The authenticated principal is read from {@link SecurityContextHolder} on the invoking thread. When an
 * {@link OAuth2AuthorizedClientManager} is used, it may also refresh expired tokens and read the current
 * servlet request. Without an authenticated user or authorized client, the invocation fails unless ambient
 * fallback is enabled, in which case the client's ambient credentials are used.
 */
public final class OAuth2CopilotTokenResolver implements CopilotTokenResolver {

    private final OAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService service;
    private final String registrationId;
    private final boolean ambientFallback;

    public OAuth2CopilotTokenResolver(OAuth2AuthorizedClientManager manager, String registrationId) {
        this(manager, registrationId, false);
    }

    public OAuth2CopilotTokenResolver(OAuth2AuthorizedClientManager manager, String registrationId, boolean ambientFallback) {
        this(manager, null, registrationId, ambientFallback);
        Assert.notNull(manager, "OAuth2AuthorizedClientManager must not be null");
    }

    public OAuth2CopilotTokenResolver(OAuth2AuthorizedClientService service, String registrationId) {
        this(service, registrationId, false);
    }

    public OAuth2CopilotTokenResolver(OAuth2AuthorizedClientService service, String registrationId, boolean ambientFallback) {
        this(null, service, registrationId, ambientFallback);
        Assert.notNull(service, "OAuth2AuthorizedClientService must not be null");
    }

    private OAuth2CopilotTokenResolver(OAuth2AuthorizedClientManager manager, OAuth2AuthorizedClientService service,
            String registrationId, boolean ambientFallback) {
        Assert.hasText(registrationId, "OAuth2 client registration ID must not be empty");
        this.manager = manager;
        this.service = service;
        this.registrationId = registrationId;
        this.ambientFallback = ambientFallback;
    }

    @Override
    public String resolveToken(CopilotAgentContext context) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return missing("no authenticated user");
        }
        OAuth2AuthorizedClient client = manager != null
                ? manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                        .principal(authentication).build())
                : service.loadAuthorizedClient(registrationId, authentication.getName());
        if (client == null || client.getAccessToken() == null) {
            return missing("no authorized client for the current user");
        }
        return client.getAccessToken().getTokenValue();
    }

    private String missing(String reason) {
        if (ambientFallback) {
            return null;
        }
        throw new IllegalStateException("Cannot resolve a Copilot token from OAuth2 client registration '"
                + registrationId + "': " + reason);
    }

    public String registrationId() {
        return registrationId;
    }

    @Override
    public String toString() {
        return "OAuth2CopilotTokenResolver[registrationId=" + registrationId + ", ambientFallback=" + ambientFallback + "]";
    }
}
