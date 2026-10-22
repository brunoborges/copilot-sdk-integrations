package io.github.brunoborges.copilot.agent;

/**
 * Resolves the GitHub token that authenticates one agent invocation, enabling per-user Copilot sessions.
 *
 * <p>The resolver is called on the <em>caller</em> thread when an agent method is invoked, so request-scoped
 * beans, {@code RequestContextHolder}, and Spring Security's {@code SecurityContextHolder} are available. The
 * returned token is captured and applied as the session's {@code gitHubToken} when the SDK session is created
 * or resumed, unless a {@link CopilotAgentCustomizer} already configured a token or token provider.
 *
 * <p>Select one resolver for every agent by declaring a single bean of this type, or select one per agent
 * with {@link CopilotAgent#tokenResolver()}. Implementations must never log the token. Exceptions fail the
 * invocation before any SDK work starts.
 */
@FunctionalInterface
public interface CopilotTokenResolver {

    /**
     * Resolves the token for an invocation.
     *
     * @param context the invocation's agent, conversation, and session identity
     * @return the GitHub token for this invocation, or {@code null} to use the client's ambient authentication
     */
    String resolveToken(CopilotAgentContext context);
}
