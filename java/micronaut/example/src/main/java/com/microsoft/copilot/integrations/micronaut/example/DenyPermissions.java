package io.github.brunoborges.copilot.micronaut.example;

import java.util.concurrent.CompletableFuture;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;

@Singleton
@Named("denyPermissions")
public class DenyPermissions implements PermissionHandler {

    private static final Logger log = LoggerFactory.getLogger(DenyPermissions.class);

    @Override
    public CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
        log.warn("Denied permission request of kind '{}': this example never approves permission requests.",
                request.getKind());
        return CompletableFuture.completedFuture(
                PermissionRequestResult.reject("This example denies all permission requests."));
    }
}
