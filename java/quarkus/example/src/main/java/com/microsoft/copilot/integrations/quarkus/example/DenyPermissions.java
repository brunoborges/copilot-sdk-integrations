package com.microsoft.copilot.integrations.quarkus.example;

import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import org.jboss.logging.Logger;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;

@ApplicationScoped
@Named("denyPermissions")
public class DenyPermissions implements PermissionHandler {

    private static final Logger log = Logger.getLogger(DenyPermissions.class);

    @Override
    public CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
        log.warnf("Denied permission request of kind '%s': this example never approves permission requests.",
                request.getKind());
        return CompletableFuture.completedFuture(
                PermissionRequestResult.reject("This example denies all permission requests."));
    }
}
