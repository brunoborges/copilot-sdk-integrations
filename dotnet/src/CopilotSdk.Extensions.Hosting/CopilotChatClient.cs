using System.Runtime.CompilerServices;
using System.Text;
using System.Threading.Channels;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// <see cref="IChatClient"/> over Copilot sessions for one agent. <see cref="ChatOptions.ConversationId"/> maps to
/// the Copilot session id.
/// </summary>
/// <remarks>
/// <para>
/// Each call is one runtime turn: the session is created (no <see cref="ChatOptions.ConversationId"/>) or resumed
/// (<see cref="ChatOptions.ConversationId"/> set), only the last user message is sent, and the session is
/// disconnected when the call ends. History stays in the runtime, so a request without a conversation id that
/// contains assistant messages is rejected with <see cref="InvalidOperationException"/>.
/// </para>
/// <para>
/// Concurrent calls for the same conversation are queued. When <see cref="CopilotOptions.MaxConcurrentTurns"/> is set,
/// extra turns wait in a bounded queue and fail with <see cref="InvalidOperationException"/> when it is full.
/// Cancellation aborts the runtime turn and throws <see cref="OperationCanceledException"/>;
/// <see cref="CopilotOptions.ResponseTimeout"/> aborts it and throws <see cref="TimeoutException"/>.
/// </para>
/// <para>
/// Created by <see cref="CopilotChatClientFactory"/> with <c>ActivatorUtilities</c>; the first constructor argument
/// is the agent name and any other parameters are resolved from DI.
/// </para>
/// </remarks>
internal sealed class CopilotChatClient : IChatClient
{
    /// <summary>The provider name reported in <see cref="ChatClientMetadata"/>.</summary>
    public const string ProviderName = "github-copilot";

    /// <summary>Lifetime reported to the runtime for tokens from <see cref="CopilotOptions.GitHubTokenProvider"/>.</summary>
    internal const long TokenExpiresInSeconds = 300;

    private static readonly TimeSpan s_abortTimeout = TimeSpan.FromSeconds(10);
    private static readonly AsyncLocal<ActiveSession?> s_activeSession = new();
    private static readonly AsyncLocal<string?> s_callingAgent = new();

    private readonly ICopilotClientProvider _clientProvider;
    private readonly ICopilotSessionGateway _gateway;
    private readonly CopilotSessionConfigFactory _sessionConfigFactory;
    private readonly IOptionsMonitor<CopilotOptions> _options;
    private readonly CopilotConversationLocks _conversationLocks;
    private readonly CopilotTurnLimiter _turnLimiter;
    private readonly CopilotTurnContextRegistry _turnContexts;
    private readonly IServiceProvider _services;
    private readonly ILogger _logger;

    public CopilotChatClient(
        string agentName,
        ICopilotClientProvider clientProvider,
        ICopilotSessionGateway gateway,
        CopilotSessionConfigFactory sessionConfigFactory,
        IOptionsMonitor<CopilotOptions> options,
        CopilotConversationLocks conversationLocks,
        CopilotTurnLimiter turnLimiter,
        CopilotTurnContextRegistry turnContexts,
        IServiceProvider services)
    {
        AgentName = agentName;
        _clientProvider = clientProvider;
        _gateway = gateway;
        _sessionConfigFactory = sessionConfigFactory;
        _options = options;
        _conversationLocks = conversationLocks;
        _turnLimiter = turnLimiter;
        _turnContexts = turnContexts;
        _services = services;
        _logger = services.GetService<ILoggerFactory>()?.CreateLogger<CopilotChatClient>() ?? NullLogger<CopilotChatClient>.Instance;
    }

    public string AgentName { get; }

    public async Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(messages);

        await using var turn = await StartTurnAsync(messages, options, streaming: false, cancellationToken).ConfigureAwait(false);
        turn.Activate(this);

        var usage = new UsageAccumulator();
        using var subscription = turn.Session.On(evt =>
        {
            if (evt is AssistantUsageEvent usageEvent && IsRootAgent(evt))
            {
                usage.Add(usageEvent.Data);
            }
        });

        AssistantMessageEvent? reply;
        try
        {
            turn.MarkSent();
            reply = await turn.Session.SendAndWaitAsync(turn.Message, turn.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException ex) when (turn.Token.IsCancellationRequested)
        {
            throw await turn.AbortAndTranslateAsync(ex).ConfigureAwait(false);
        }

        turn.MarkCompleted();

        var response = reply is null
            ? new ChatResponse()
            : new ChatResponse(new ChatMessage(ChatRole.Assistant, reply.Data.Content)
            {
                MessageId = reply.Data.MessageId,
                CreatedAt = NullIfDefault(reply.Timestamp),
                RawRepresentation = reply,
            });

        response.ConversationId = turn.Session.SessionId;
        response.ResponseId = reply?.Data.MessageId;
        response.ModelId = reply?.Data.Model ?? usage.Model ?? turn.Model;
        response.CreatedAt = reply is null ? null : NullIfDefault(reply.Timestamp);
        response.FinishReason = ChatFinishReason.Stop;
        response.Usage = usage.ToUsageDetails();
        response.RawRepresentation = reply;
        return response;
    }

    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(messages);

        await using var turn = await StartTurnAsync(messages, options, streaming: true, cancellationToken).ConfigureAwait(false);
        turn.Activate(this);

        var events = Channel.CreateUnbounded<SessionEvent>(new UnboundedChannelOptions { SingleReader = true });
        using var subscription = turn.Session.On(evt =>
        {
            if (IsRootAgent(evt))
            {
                events.Writer.TryWrite(evt);
            }
        });

        string messageId;
        try
        {
            turn.MarkSent();
            messageId = await turn.Session.SendAsync(turn.Message, turn.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException ex) when (turn.Token.IsCancellationRequested)
        {
            throw await turn.AbortAndTranslateAsync(ex).ConfigureAwait(false);
        }

        var state = new StreamState(turn.Session.SessionId, messageId, turn.Model);
        while (true)
        {
            SessionEvent evt;
            try
            {
                evt = await events.Reader.ReadAsync(turn.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException ex) when (turn.Token.IsCancellationRequested)
            {
                throw await turn.AbortAndTranslateAsync(ex).ConfigureAwait(false);
            }

            var update = state.Process(evt);
            if (state.Completed)
            {
                turn.MarkCompleted();
            }

            if (update is not null)
            {
                yield return update;
            }

            if (state.Completed)
            {
                yield break;
            }
        }
    }

    public object? GetService(Type serviceType, object? serviceKey = null)
    {
        ArgumentNullException.ThrowIfNull(serviceType);

        if (serviceKey is not null)
        {
            return null;
        }

        if (serviceType == typeof(ChatClientMetadata))
        {
            return new ChatClientMetadata(ProviderName, providerUri: null, defaultModelId: _options.Get(AgentName).Model);
        }

        if (serviceType == typeof(CopilotClient))
        {
            return _clientProvider.Client;
        }

        if (serviceType == typeof(CopilotSession))
        {
            return s_activeSession.Value is { } active && ReferenceEquals(active.Owner, this) ? active.Session : null;
        }

        return serviceType.IsInstanceOfType(this) ? this : null;
    }

    public void Dispose()
    {
    }

    private async Task<Turn> StartTurnAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options,
        bool streaming,
        CancellationToken cancellationToken)
    {
        var request = ChatRequest.Parse(messages, options);
        var agentOptions = _options.Get(AgentName);
        var source = ResolveMessageSource(agentOptions);
        var callerContext = ExecutionContext.Capture();
        var toolContext = new ToolContextHolder();

        IDisposable? conversationLock = null;
        IDisposable? slot = null;
        CancellationTokenSource? cts = null;
        ICopilotSessionHandle? session = null;
        try
        {
            if (request.ConversationId is { } conversationId)
            {
                conversationLock = await _conversationLocks.AcquireAsync(conversationId, cancellationToken).ConfigureAwait(false);
            }

            slot = await _turnLimiter.AcquireAsync(cancellationToken).ConfigureAwait(false);

            cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(agentOptions.ResponseTimeout);
            var token = cts.Token;

            SessionConfigBase config = request.ConversationId is null
                ? _sessionConfigFactory.CreateSessionConfig(AgentName, _services)
                : _sessionConfigFactory.CreateResumeSessionConfig(AgentName, _services);

            ApplyChatOptions(config, request, options, streaming, toolContext);
            await ApplyTokenProviderAsync(config, agentOptions, callerContext, token).ConfigureAwait(false);

            if (request.ConversationId is { } id)
            {
                Log.Resuming(_logger, AgentName, id);
                session = await _gateway.ResumeSessionAsync(id, (ResumeSessionConfig)config, token).ConfigureAwait(false);
            }
            else
            {
                session = await _gateway.CreateSessionAsync((SessionConfig)config, token).ConfigureAwait(false);
                Log.Created(_logger, AgentName, session.SessionId);
            }

            var message = request.ToMessageOptions(options);
            if (source is not null)
            {
                message.Source = source;
            }

            return new Turn(this, session, message, cts, cancellationToken, agentOptions.ResponseTimeout,
                config.Model, toolContext, conversationLock, slot);
        }
        catch (Exception ex)
        {
            if (session is not null)
            {
                await DisposeQuietlyAsync(session).ConfigureAwait(false);
            }

            var timedOut = cts?.IsCancellationRequested == true && !cancellationToken.IsCancellationRequested;
            cts?.Dispose();
            slot?.Dispose();
            conversationLock?.Dispose();

            if (ex is OperationCanceledException && timedOut)
            {
                throw new TimeoutException(TimeoutMessage(agentOptions.ResponseTimeout), ex);
            }

            throw;
        }
    }

    private static void ApplyChatOptions(SessionConfigBase config, ChatRequest request, ChatOptions? options, bool streaming, ToolContextHolder toolContext)
    {
        if (streaming)
        {
            config.Streaming = true;
        }

        if (options is null)
        {
            return;
        }

        if (!string.IsNullOrWhiteSpace(options.ModelId))
        {
            config.Model = options.ModelId;
        }

        if (MapReasoningEffort(options.Reasoning?.Effort) is { } effort)
        {
            config.ReasoningEffort = effort;
        }

        if (options.Tools is { Count: > 0 } tools)
        {
            foreach (var tool in tools)
            {
                // Only invocable functions can run in the runtime; other AITool kinds have no Copilot equivalent.
                if (tool is not AIFunction function)
                {
                    continue;
                }

                config.Tools ??= [];
                config.Tools.Add(new TurnToolFunction(function, toolContext));
                if (config.AvailableTools is { } available && !available.Contains(function.Name))
                {
                    available.Add(function.Name);
                }
            }
        }

        if (request.ConversationId is null && request.SystemText is { } systemText)
        {
            AppendSystemMessage(config, systemText);
        }
    }

    /// <summary>
    /// The source for a turn: a <see cref="CopilotMessageSources.BeginScope"/> scope, else <c>agent-&lt;caller&gt;</c>
    /// when called from a tool of another agent's turn, else the configured <see cref="CopilotOptions.MessageSource"/>.
    /// Inference never yields <c>user</c>.
    /// </summary>
    private static MessageSource? ResolveMessageSource(CopilotOptions agentOptions)
    {
        if (CopilotMessageSources.Current is { } scoped)
        {
            return scoped;
        }

        // Tools run in the ExecutionContext captured by Turn.Activate, which records the calling agent.
        if (s_callingAgent.Value is { } callingAgent)
        {
            var caller = callingAgent.Length == 0 ? "default" : callingAgent;
            if (CopilotMessageSources.IsValidAgentId(caller))
            {
                return MessageSource.Agent(caller);
            }
        }

        return CopilotMessageSources.Parse(agentOptions.MessageSource);
    }

    internal static void AppendSystemMessage(SessionConfigBase config, string text)
    {
        if (config.SystemMessage is null)
        {
            config.SystemMessage = new SystemMessageConfig { Mode = SystemMessageMode.Append, Content = text };
        }
        else
        {
            config.SystemMessage.Content = string.IsNullOrEmpty(config.SystemMessage.Content)
                ? text
                : config.SystemMessage.Content + "\n\n" + text;
        }
    }

    internal static string? MapReasoningEffort(ReasoningEffort? effort) => effort switch
    {
        ReasoningEffort.Low => "low",
        ReasoningEffort.Medium => "medium",
        ReasoningEffort.High => "high",
        ReasoningEffort.ExtraHigh => "xhigh",
        _ => null,
    };

    private async Task ApplyTokenProviderAsync(
        SessionConfigBase config,
        CopilotOptions agentOptions,
        ExecutionContext? callerContext,
        CancellationToken cancellationToken)
    {
        if (agentOptions.GitHubTokenProvider is not { } provider)
        {
            return;
        }

        var initialToken = await provider(_services, cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrEmpty(initialToken))
        {
            throw new InvalidOperationException(
                $"The GitHubTokenProvider of Copilot agent '{DisplayName(AgentName)}' returned no token. A per-user token is required " +
                "when GitHubTokenProvider is set; the call does not fall back to the service identity.");
        }

        // The SDK calls this on its RPC thread. Initial requests reuse the token fetched on the caller's thread;
        // refreshes call the provider again under the caller's ExecutionContext (HttpContext, Activity, ...).
        config.GitHubToken = null;
        config.GitHubTokenProvider = async args =>
        {
            var token = initialToken;
            if (args.Reason == GitHubTokenRequestReason.Refresh)
            {
                token = await RunInContext(callerContext, () => provider(_services, cancellationToken)).ConfigureAwait(false);
            }

            return string.IsNullOrEmpty(token)
                ? GitHubTokenProviderResult.Cancel()
                : GitHubTokenProviderResult.FromToken(new GitHub.Copilot.GitHubToken { AccessToken = token, ExpiresIn = TokenExpiresInSeconds });
        };
    }

    internal static Task<T> RunInContext<T>(ExecutionContext? context, Func<Task<T>> action)
    {
        if (context is null)
        {
            return action();
        }

        Task<T>? task = null;
        ExecutionContext.Run(context.CreateCopy(), _ => task = action(), null);
        return task!;
    }

    private static bool IsRootAgent(SessionEvent evt) => string.IsNullOrEmpty(evt.AgentId);

    private static DateTimeOffset? NullIfDefault(DateTimeOffset value) => value == default ? null : value;

    private static string DisplayName(string agentName) => agentName.Length == 0 ? "(default)" : agentName;

    private static string TimeoutMessage(TimeSpan timeout) =>
        $"The Copilot turn did not complete within the response timeout ({timeout}). The turn was aborted. " +
        "Raise Copilot:ResponseTimeout if turns legitimately take longer.";

    private async Task DisposeQuietlyAsync(ICopilotSessionHandle session)
    {
        try
        {
            await session.DisposeAsync().ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Log.DisconnectFailed(_logger, ex, session.SessionId);
        }
    }

    /// <summary>A parsed request: the prompt and attachments of the last user message, and new-session instructions.</summary>
    internal sealed class ChatRequest
    {
        private ChatRequest(string? conversationId, string prompt, List<Attachment>? attachments, string? systemText)
        {
            ConversationId = conversationId;
            Prompt = prompt;
            Attachments = attachments;
            SystemText = systemText;
        }

        public string? ConversationId { get; }

        public string Prompt { get; }

        public List<Attachment>? Attachments { get; }

        /// <summary>System messages and <see cref="ChatOptions.Instructions"/>; applied to new sessions only.</summary>
        public string? SystemText { get; }

        public static ChatRequest Parse(IEnumerable<ChatMessage> messages, ChatOptions? options)
        {
            var list = messages as IReadOnlyList<ChatMessage> ?? [.. messages];
            var conversationId = string.IsNullOrWhiteSpace(options?.ConversationId) ? null : options.ConversationId;

            if (conversationId is null && list.Any(m => m.Role == ChatRole.Assistant))
            {
                throw new InvalidOperationException(
                    "The request contains assistant messages but no ChatOptions.ConversationId. Copilot keeps conversation " +
                    "history in the runtime: pass the ConversationId returned by the previous ChatResponse and send only the " +
                    "new user message instead of replaying the transcript.");
            }

            // The new turn is every user message after the last assistant message (e.g. MEAI may append the
            // schema prompt as a separate user message); earlier history already lives in the runtime session.
            var start = list.Count;
            while (start > 0 && list[start - 1].Role != ChatRole.Assistant)
            {
                start--;
            }

            var userMessages = list.Skip(start).Where(m => m.Role == ChatRole.User).ToList();
            if (userMessages.Count == 0)
            {
                throw new ArgumentException("The request must contain a user message.", nameof(messages));
            }

            List<Attachment>? attachments = null;
            var promptBuilder = new StringBuilder();
            foreach (var userMessage in userMessages)
            {
                if (!string.IsNullOrWhiteSpace(userMessage.Text))
                {
                    AppendParagraph(promptBuilder, userMessage.Text);
                }

                foreach (var content in userMessage.Contents)
                {
                    if (content is DataContent data)
                    {
                        attachments ??= [];
                        attachments.Add(new AttachmentBlob
                        {
                            Data = Convert.ToBase64String(data.Data.Span),
                            MimeType = data.MediaType,
                            DisplayName = data.Name,
                        });
                    }

                    // TODO: map UriContent and other content kinds to Copilot attachments when there is a clear equivalent.
                }
            }

            var prompt = promptBuilder.ToString();
            if (string.IsNullOrWhiteSpace(prompt) && attachments is null)
            {
                throw new ArgumentException("The last user message has no text or data content.", nameof(messages));
            }

            string? systemText = null;
            if (conversationId is null)
            {
                var builder = new StringBuilder();
                foreach (var message in list)
                {
                    if (message.Role == ChatRole.System && !string.IsNullOrWhiteSpace(message.Text))
                    {
                        AppendParagraph(builder, message.Text);
                    }
                }

                if (!string.IsNullOrWhiteSpace(options?.Instructions))
                {
                    AppendParagraph(builder, options.Instructions);
                }

                systemText = builder.Length == 0 ? null : builder.ToString();
            }

            return new ChatRequest(conversationId, prompt, attachments, systemText);
        }

        private static readonly AIJsonSchemaTransformOptions StrictSchemaOptions = new()
        {
            DisallowAdditionalProperties = true,
            RequireAllProperties = true,
        };

        public MessageOptions ToMessageOptions(ChatOptions? options)
        {
            var message = new MessageOptions { Prompt = Prompt };
            if (Attachments is not null)
            {
                message.Attachments = Attachments;
            }

            // Structured output: MEAI's GetResponseAsync<T> sends a JSON schema and parses the reply text, which the
            // runtime constrains to the schema (the same mechanism as the SDK's SendAndWaitAsync<T>). Models enforce
            // strict mode, which requires additionalProperties:false and every property listed as required.
            if (options?.ResponseFormat is ChatResponseFormatJson { Schema: { } schema })
            {
                message.ResponseSchema = AIJsonUtilities.TransformSchema(schema, StrictSchemaOptions);
            }

            return message;
        }

        private static void AppendParagraph(StringBuilder builder, string text)
        {
            if (builder.Length > 0)
            {
                builder.Append("\n\n");
            }

            builder.Append(text);
        }
    }

    /// <summary>The state of one in-flight call: the session, the acquired locks, and the turn's cancellation.</summary>
    private sealed class Turn(
        CopilotChatClient owner,
        ICopilotSessionHandle session,
        MessageOptions message,
        CancellationTokenSource cts,
        CancellationToken callerToken,
        TimeSpan timeout,
        string? model,
        ToolContextHolder toolContext,
        IDisposable? conversationLock,
        IDisposable slot) : IAsyncDisposable
    {
        private bool _sent;
        private bool _completed;
        private bool _registered;

        public ICopilotSessionHandle Session { get; } = session;

        public MessageOptions Message { get; } = message;

        public CancellationToken Token { get; } = cts.Token;

        public string? Model { get; } = model;

        /// <summary>
        /// Makes the session visible through <see cref="GetService"/> and publishes the caller's context for tools.
        /// Call from the public method's frame so the async-local value flows into the captured context.
        /// </summary>
        public void Activate(CopilotChatClient client)
        {
            s_activeSession.Value = Session.Session is { } sdkSession ? new ActiveSession(client, sdkSession) : null;
            s_callingAgent.Value = client.AgentName;
            CopilotMessageSources.Suspend();
            var context = ExecutionContext.Capture();
            toolContext.ExecutionContext = context;
            owner._turnContexts.Set(Session.SessionId, new TurnContext(context, owner._services, owner.AgentName, Token));
            _registered = true;
        }

        public void MarkSent() => _sent = true;

        public void MarkCompleted() => _completed = true;

        /// <summary>Aborts the runtime turn and returns the exception to throw for a cancellation or timeout.</summary>
        public async Task<Exception> AbortAndTranslateAsync(OperationCanceledException ex)
        {
            await AbortAsync().ConfigureAwait(false);
            _completed = true;

            return callerToken.IsCancellationRequested
                ? new OperationCanceledException("The Copilot turn was canceled and aborted.", ex, callerToken)
                : new TimeoutException(TimeoutMessage(timeout), ex);
        }

        private async Task AbortAsync()
        {
            try
            {
                using var abortCts = new CancellationTokenSource(s_abortTimeout);
                await Session.AbortAsync(abortCts.Token).ConfigureAwait(false);
                Log.Aborted(owner._logger, Session.SessionId);
            }
            catch (Exception abortError)
            {
                Log.AbortFailed(owner._logger, abortError, Session.SessionId);
            }
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                // A stream abandoned mid-turn, or an unexpected failure, leaves the runtime working: stop it.
                if (_sent && !_completed)
                {
                    await AbortAsync().ConfigureAwait(false);
                }

                if (_registered)
                {
                    owner._turnContexts.Remove(Session.SessionId);
                }

                await owner.DisposeQuietlyAsync(Session).ConfigureAwait(false);
            }
            finally
            {
                cts.Dispose();
                slot.Dispose();
                conversationLock?.Dispose();
            }
        }
    }

    /// <summary>Turns root-agent session events into streaming updates.</summary>
    private sealed class StreamState(string conversationId, string messageId, string? model)
    {
        private readonly HashSet<string> _streamedMessageIds = new(StringComparer.Ordinal);
        private bool _started;

        public bool Completed { get; private set; }

        public ChatResponseUpdate? Process(SessionEvent evt)
        {
            switch (evt)
            {
                case UserMessageEvent user when user.Data.MessageId == messageId:
                    _started = true;
                    return null;

                case AssistantMessageDeltaEvent delta when _started:
                    _streamedMessageIds.Add(delta.Data.MessageId);
                    return Update(evt, delta.Data.DeltaContent, delta.Data.MessageId);

                case AssistantMessageEvent assistant when _started || assistant.Data.OriginatingMessageId == messageId:
                    _started = true;

                    // Without streaming deltas (e.g. a model that does not stream), emit the whole message once.
                    return _streamedMessageIds.Contains(assistant.Data.MessageId) || string.IsNullOrEmpty(assistant.Data.Content)
                        ? null
                        : Update(evt, assistant.Data.Content, assistant.Data.MessageId);

                case AssistantUsageEvent usage when _started:
                    var accumulator = new UsageAccumulator();
                    accumulator.Add(usage.Data);
                    var usageUpdate = new ChatResponseUpdate(ChatRole.Assistant, [new UsageContent(accumulator.ToUsageDetails()!)])
                    {
                        ConversationId = conversationId,
                        ModelId = usage.Data.Model,
                        RawRepresentation = evt,
                    };
                    return usageUpdate;

                case SessionErrorEvent error when string.IsNullOrEmpty(error.Data.OriginatingMessageId)
                    ? _started
                    : error.Data.OriginatingMessageId == messageId:
                    throw new InvalidOperationException($"Session error: {error.Data.Message}");

                case SessionIdleEvent idle when _started && idle.Data.Mode != SessionMode.Autopilot:
                    if (idle.Data.Aborted == true)
                    {
                        throw new InvalidOperationException("The Copilot session was aborted before the turn completed.");
                    }

                    Completed = true;
                    return new ChatResponseUpdate
                    {
                        Role = ChatRole.Assistant,
                        ConversationId = conversationId,
                        ModelId = model,
                        FinishReason = ChatFinishReason.Stop,
                        RawRepresentation = evt,
                    };

                default:
                    return null;
            }
        }

        private ChatResponseUpdate Update(SessionEvent evt, string text, string assistantMessageId) =>
            new(ChatRole.Assistant, text)
            {
                ConversationId = conversationId,
                MessageId = assistantMessageId,
                ModelId = model,
                CreatedAt = NullIfDefault(evt.Timestamp),
                RawRepresentation = evt,
            };
    }

    /// <summary>Sums <see cref="AssistantUsageEvent"/>s of one turn (a turn can make several model calls).</summary>
    private sealed class UsageAccumulator
    {
        private readonly object _gate = new();
        private long _input;
        private long _output;
        private long _cached;
        private bool _any;

        public string? Model { get; private set; }

        public void Add(AssistantUsageData data)
        {
            lock (_gate)
            {
                _any = true;
                _input += data.InputTokens ?? 0;
                _output += data.OutputTokens ?? 0;
                _cached += data.CacheReadTokens ?? 0;
                Model ??= data.Model;
            }
        }

        public UsageDetails? ToUsageDetails()
        {
            lock (_gate)
            {
                return _any
                    ? new UsageDetails
                    {
                        InputTokenCount = _input,
                        OutputTokenCount = _output,
                        TotalTokenCount = _input + _output,
                        CachedInputTokenCount = _cached == 0 ? null : _cached,
                    }
                    : null;
            }
        }
    }

    /// <summary>The caller's <see cref="ExecutionContext"/>, set once the turn is activated.</summary>
    private sealed class ToolContextHolder
    {
        public ExecutionContext? ExecutionContext { get; set; }
    }

    /// <summary>
    /// A per-call tool from <see cref="ChatOptions.Tools"/>: runs without a permission prompt (the caller supplied it)
    /// and under the caller's <see cref="ExecutionContext"/>.
    /// </summary>
    private sealed class TurnToolFunction(AIFunction inner, ToolContextHolder context) : DelegatingAIFunction(inner)
    {
        private readonly Dictionary<string, object?> _additionalProperties =
            new(inner.AdditionalProperties) { ["skip_permission"] = true };

        public override IReadOnlyDictionary<string, object?> AdditionalProperties => _additionalProperties;

        protected override ValueTask<object?> InvokeCoreAsync(AIFunctionArguments arguments, CancellationToken cancellationToken) =>
            new(RunInContext(context.ExecutionContext, () => base.InvokeCoreAsync(arguments, cancellationToken).AsTask()));
    }

    private sealed record ActiveSession(CopilotChatClient Owner, CopilotSession Session);

    private static class Log
    {
        private static readonly Action<ILogger, string, string, Exception?> s_created = LoggerMessage.Define<string, string>(
            LogLevel.Debug, new EventId(1, "CopilotSessionCreated"), "Copilot agent '{AgentName}' created session {SessionId}.");

        private static readonly Action<ILogger, string, string, Exception?> s_resuming = LoggerMessage.Define<string, string>(
            LogLevel.Debug, new EventId(2, "CopilotSessionResuming"), "Copilot agent '{AgentName}' resuming session {SessionId}.");

        private static readonly Action<ILogger, string, Exception?> s_aborted = LoggerMessage.Define<string>(
            LogLevel.Information, new EventId(3, "CopilotTurnAborted"), "Aborted the Copilot turn in session {SessionId}.");

        private static readonly Action<ILogger, string, Exception?> s_abortFailed = LoggerMessage.Define<string>(
            LogLevel.Warning, new EventId(4, "CopilotTurnAbortFailed"), "Failed to abort the Copilot turn in session {SessionId}.");

        private static readonly Action<ILogger, string, Exception?> s_disconnectFailed = LoggerMessage.Define<string>(
            LogLevel.Warning, new EventId(5, "CopilotSessionDisconnectFailed"), "Failed to disconnect Copilot session {SessionId}.");

        public static void Created(ILogger logger, string agentName, string sessionId) => s_created(logger, DisplayName(agentName), sessionId, null);

        public static void Resuming(ILogger logger, string agentName, string sessionId) => s_resuming(logger, DisplayName(agentName), sessionId, null);

        public static void Aborted(ILogger logger, string sessionId) => s_aborted(logger, sessionId, null);

        public static void AbortFailed(ILogger logger, Exception ex, string sessionId) => s_abortFailed(logger, sessionId, ex);

        public static void DisconnectFailed(ILogger logger, Exception ex, string sessionId) => s_disconnectFailed(logger, sessionId, ex);
    }
}
