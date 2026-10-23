package com.microsoft.copilot.integrations.springboot.security;

import java.time.Instant;

import com.github.copilot.CopilotClient;
import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotTokenResolver;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotSecurityAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2CopilotTokenResolverTests {

    private static final CopilotAgentContext CONTEXT = new CopilotAgentContext("advisor", "conversation", "agent-1");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerAuthorizesTheCurrentPrincipal() {
        var manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenReturn(client("manager-token"));
        authenticate("alice");
        var resolver = new OAuth2CopilotTokenResolver(manager, "github");
        assertThat(resolver.resolveToken(CONTEXT)).isEqualTo("manager-token");
        ArgumentCaptor<OAuth2AuthorizeRequest> request = ArgumentCaptor.captor();
        verify(manager).authorize(request.capture());
        assertThat(request.getValue().getClientRegistrationId()).isEqualTo("github");
        assertThat(request.getValue().getPrincipal().getName()).isEqualTo("alice");
        assertThat(resolver.toString()).doesNotContain("manager-token");
    }

    @Test
    void serviceLoadsTheCurrentPrincipalsClient() {
        var service = mock(OAuth2AuthorizedClientService.class);
        when(service.loadAuthorizedClient("github", "bob")).thenReturn(client("service-token"));
        authenticate("bob");
        assertThat(new OAuth2CopilotTokenResolver(service, "github").resolveToken(CONTEXT)).isEqualTo("service-token");
    }

    @Test
    void partitionsSessionsByRegistrationAndPrincipal() {
        var service = mock(OAuth2AuthorizedClientService.class);
        var strict = new OAuth2CopilotTokenResolver(service, "github");
        var fallback = new OAuth2CopilotTokenResolver(service, "github", true);
        assertThatThrownBy(() -> strict.partitionKey("agent", "one")).hasMessageContaining("no authenticated user");
        assertThat(fallback.partitionKey("agent", "one")).isEqualTo(CopilotTokenResolver.SHARED);
        authenticate("alice");
        assertThat(strict.partitionKey("agent", "one")).isEqualTo("oauth2:github:alice");
        authenticate("bob");
        assertThat(fallback.partitionKey("agent", "one")).isEqualTo("oauth2:github:bob");
    }

    @Test
    void missingUserOrClientFailsUnlessAmbientFallbackIsEnabled() {
        var service = mock(OAuth2AuthorizedClientService.class);
        var strict = new OAuth2CopilotTokenResolver(service, "github");
        var fallback = new OAuth2CopilotTokenResolver(service, "github", true);
        assertThatThrownBy(() -> strict.resolveToken(CONTEXT)).hasMessageContaining("no authenticated user");
        assertThat(fallback.resolveToken(CONTEXT)).isNull();
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> strict.resolveToken(CONTEXT)).hasMessageContaining("no authenticated user");
        authenticate("carol");
        assertThatThrownBy(() -> strict.resolveToken(CONTEXT)).hasMessageContaining("no authorized client");
        assertThat(fallback.resolveToken(CONTEXT)).isNull();
    }

    @Test
    void autoConfiguredOnlyWithARegistrationIdAndOAuth2ClientOnTheClasspath() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CopilotSecurityAutoConfiguration.class))
                .withBean(OAuth2AuthorizedClientService.class, () -> mock(OAuth2AuthorizedClientService.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(CopilotTokenResolver.class));
        runner.withPropertyValues("copilot.security.oauth2.registration-id=github",
                "copilot.security.oauth2.ambient-fallback=true").run(context -> {
                    assertThat(context).hasSingleBean(OAuth2CopilotTokenResolver.class);
                    var resolver = context.getBean(OAuth2CopilotTokenResolver.class);
                    assertThat(resolver.registrationId()).isEqualTo("github");
                    assertThat(resolver.resolveToken(CONTEXT)).isNull();
                });
        runner.withPropertyValues("copilot.security.oauth2.registration-id=github")
                .withClassLoader(new FilteredClassLoader(OAuth2AuthorizedClientManager.class))
                .run(context -> assertThat(context).doesNotHaveBean(CopilotTokenResolver.class));
        runner.withPropertyValues("copilot.security.oauth2.registration-id=github")
                .withClassLoader(new FilteredClassLoader(CopilotClient.class))
                .run(context -> assertThat(context).doesNotHaveBean(CopilotTokenResolver.class));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CopilotSecurityAutoConfiguration.class))
                .withPropertyValues("copilot.security.oauth2.registration-id=github")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("OAuth2AuthorizedClientManager or OAuth2AuthorizedClientService"));
    }

    private static void authenticate(String name) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(name, "n/a", "ROLE_USER"));
    }

    private static OAuth2AuthorizedClient client(String token) {
        var registration = ClientRegistration.withRegistrationId("github").clientId("id")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri("https://github.com/token").build();
        return new OAuth2AuthorizedClient(registration, "principal",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, Instant.now(), Instant.now().plusSeconds(60)));
    }
}
