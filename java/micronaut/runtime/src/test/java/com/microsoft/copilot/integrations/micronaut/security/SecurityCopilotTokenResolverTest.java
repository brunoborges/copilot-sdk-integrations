package com.microsoft.copilot.integrations.micronaut.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotTokenResolver;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;

class SecurityCopilotTokenResolverTest {

    private final SecurityService security = mock(SecurityService.class);
    private final CopilotAgentContext context = new CopilotAgentContext("agent", "c-1", null);

    @Test
    void usesTheAuthenticatedUsersTokenAndPartitionsByUser() {
        when(security.getAuthentication()).thenReturn(Optional.of(
                Authentication.build("octocat", List.of(), Map.of("accessToken", "gho_token"))));
        var resolver = new SecurityCopilotTokenResolver(security, "accessToken", false);

        assertThat(resolver.resolveToken(context)).isEqualTo("gho_token");
        assertThat(resolver.partitionKey("agent", "c-1")).isEqualTo("identity:octocat");
    }

    @Test
    void readsTheConfiguredAttribute() {
        when(security.getAuthentication()).thenReturn(Optional.of(
                Authentication.build("octocat", List.of(), Map.of("github", "gho_other"))));

        assertThat(new SecurityCopilotTokenResolver(security, "github", false).resolveToken(context))
                .isEqualTo("gho_other");
    }

    @Test
    void failsWithoutAUserOrToken() {
        when(security.getAuthentication()).thenReturn(Optional.empty());
        var resolver = new SecurityCopilotTokenResolver(security, "accessToken", false);

        assertThatThrownBy(() -> resolver.resolveToken(context)).hasMessageContaining("no authenticated user");
        assertThatThrownBy(() -> resolver.partitionKey("agent", "c-1")).hasMessageContaining("no authenticated user");

        when(security.getAuthentication()).thenReturn(Optional.of(Authentication.build("octocat")));
        assertThatThrownBy(() -> resolver.resolveToken(context)).hasMessageContaining("'accessToken'");
    }

    @Test
    void fallsBackToAmbientCredentialsWhenAllowed() {
        when(security.getAuthentication()).thenReturn(Optional.empty());
        var resolver = new SecurityCopilotTokenResolver(security, "accessToken", true);

        assertThat(resolver.resolveToken(context)).isNull();
        assertThat(resolver.partitionKey("agent", "c-1")).isEqualTo(CopilotTokenResolver.SHARED);
    }
}
