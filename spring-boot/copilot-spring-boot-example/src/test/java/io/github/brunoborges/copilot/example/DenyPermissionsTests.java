package io.github.brunoborges.copilot.example;

import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DenyPermissionsTests {

    @ParameterizedTest
    @ValueSource(strings = {"read", "write", "shell", "url"})
    void explicitlyRejectsPermissionRequests(String kind) {
        var request = mock(PermissionRequest.class);
        var invocation = mock(PermissionInvocation.class);
        when(request.getKind()).thenReturn(kind);
        var result = new DenyPermissions().handle(request, invocation).join();
        assertThat(result.getKind()).isEqualTo("reject");
        assertThat(result.getFeedback()).isNotBlank();
        verifyNoInteractions(invocation);
    }
}
