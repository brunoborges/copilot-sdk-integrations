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
 *
 * <p><strong>Session isolation.</strong> Agent sessions are keyed by agent and conversation ID. Because a
 * per-user token changes whose identity a session acts for, a resolver that returns a token must also return a
 * {@linkplain #partitionKey(String, String) partition key} (typically the stable principal name) so that two
 * users presenting the same conversation ID never share or resume each other's session. Invocations that
 * resolve a token without a partition key fail; return {@link #SHARED} to deliberately share sessions.
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

    /** Partition key that deliberately shares sessions across every caller of a conversation ID. */
    String SHARED = "";

    /**
     * Returns a stable, non-secret key that isolates sessions per caller, called on the caller thread before
     * {@link #resolveToken(CopilotAgentContext)}. The key is hashed into the session ID and never sent as-is.
     *
     * @param agentName the agent name
     * @param conversationId the application conversation ID
     * @return the caller's partition (for example the principal name), {@link #SHARED} to share sessions
     *         across callers, or {@code null} when undeclared; undeclared fails any invocation that resolves a token
     */
    default String partitionKey(String agentName, String conversationId) {
        return null;
    }
}
