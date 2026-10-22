package io.github.brunoborges.copilot.quarkus.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Build-time switches of the Copilot extension. */
@ConfigMapping(prefix = "quarkus.copilot")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface CopilotBuildTimeConfig {

    /** Whether the Copilot client, agents, and integrations are registered at all. */
    @WithDefault("true")
    boolean enabled();

    /** SmallRye Health integration. */
    Health health();

    /** Micrometer integration. */
    Metrics metrics();

    /** Diagnostics endpoint. */
    Endpoint endpoint();

    /** {@code /q/info} contribution. */
    Info info();

    /** Quarkus Security integration. */
    Security security();

    /** Scripted test runtime provided by {@code copilot-quarkus-test}. */
    TestRuntime testRuntime();

    interface Health {
        /** Whether a {@code copilot} readiness check is registered when SmallRye Health is present. */
        @WithDefault("true")
        boolean enabled();
    }

    interface Metrics {
        /** Whether agent invocations and tool calls are timed when Micrometer is present. */
        @WithDefault("true")
        boolean enabled();
    }

    interface Endpoint {
        /**
         * Whether the read-only diagnostics endpoint is served at {@code /q/copilot} (requires
         * {@code quarkus-vertx-http}).
         */
        @WithDefault("false")
        boolean enabled();
    }

    interface Info {
        /** Whether a {@code copilot} section is contributed to {@code /q/info} when {@code quarkus-info} is present. */
        @WithDefault("true")
        boolean enabled();
    }

    interface Security {
        /** Identity-based token resolution. */
        IdentityTokenResolver identityTokenResolver();

        interface IdentityTokenResolver {
            /**
             * Whether agents authenticate as the current {@code SecurityIdentity}, using its bearer token, when Quarkus
             * Security is present.
             */
            @WithDefault("false")
            boolean enabled();
        }
    }

    interface TestRuntime {
        /** Whether {@code copilot-quarkus-test}, when on the class path, replaces the client with a scripted runtime. */
        @WithDefault("true")
        boolean enabled();
    }
}
