using System.Collections.Concurrent;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading.Channels;
using GitHub.Copilot;
using GitHub.Copilot.Rpc;
using Microsoft.Extensions.AI;

/// <summary>
/// Everything SupportApi gets from AddCopilot, written by hand: session configuration, tools with a DI scope per call,
/// the permission policy, per-conversation locking, timeouts that abort the turn, streaming, and structured output.
/// </summary>
public sealed class SupportAgent
{
    private const string Instructions = "You are Contoso's customer support assistant. Use the tools to look up and refund orders. Keep answers short.";
    private static readonly TimeSpan s_timeout = TimeSpan.FromMinutes(2);

    private readonly CopilotClient _copilot;
    private readonly IServiceScopeFactory _scopes;
    private readonly ILogger<SupportAgent> _logger;
    private readonly string? _model;
    private readonly AIFunction[] _tools;
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _conversationLocks = new();

    public SupportAgent(CopilotClient copilot, IServiceScopeFactory scopes, IConfiguration configuration, ILogger<SupportAgent> logger)
    {
        _copilot = copilot;
        _scopes = scopes;
        _logger = logger;
        _model = configuration["Copilot:Model"];
        _tools =
        [
            CopilotTool.DefineTool(
                ([Description("The order id, for example 1001.")] int orderId) => InScope(orders => orders.Find(orderId)),
                new CopilotToolOptions { SkipPermission = true },
                new AIFunctionFactoryOptions { Name = "lookup_order", Description = "Looks up an order by its id." }),
            CopilotTool.DefineTool(
                ([Description("The order id.")] int orderId, [Description("The amount to refund, in USD.")] decimal amount) =>
                    InScope(orders => orders.Refund(orderId, amount)),
                factoryOptions: new AIFunctionFactoryOptions { Name = "refund_order", Description = "Refunds part or all of an order." }),
        ];
    }

    public async Task<ChatReply> ChatAsync(ChatRequest request, CancellationToken cancellationToken)
    {
        await using var conversation = await LockAsync(request.ConversationId, cancellationToken);
        await using var session = await OpenAsync(request.ConversationId, streaming: false, cancellationToken);
        try
        {
            var reply = await session.SendAndWaitAsync(request.Message, s_timeout, cancellationToken);
            return new ChatReply(reply?.Data.Content ?? string.Empty, session.SessionId);
        }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
            await session.AbortAsync(CancellationToken.None); // the SDK's timeout does not stop the agent's work
            throw;
        }
    }

    public async IAsyncEnumerable<string> StreamAsync(ChatRequest request, [EnumeratorCancellation] CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(s_timeout);
        await using var conversation = await LockAsync(request.ConversationId, timeout.Token);
        await using var session = await OpenAsync(request.ConversationId, streaming: true, timeout.Token);

        // Events arrive on the SDK's thread; buffer them, and ignore sub-agent events.
        var events = Channel.CreateUnbounded<SessionEvent>();
        using var subscription = session.On<SessionEvent>(evt =>
        {
            if (string.IsNullOrEmpty(evt.AgentId))
            {
                events.Writer.TryWrite(evt);
            }
        });

        var messageId = await session.SendAsync(request.Message, timeout.Token);
        var started = false; // a resumed session can replay earlier events; start at our own message
        while (true)
        {
            SessionEvent evt;
            try
            {
                evt = await events.Reader.ReadAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                await session.AbortAsync(CancellationToken.None);
                throw;
            }

            switch (evt)
            {
                case UserMessageEvent user when user.Data.MessageId == messageId:
                    started = true;
                    break;
                case AssistantMessageDeltaEvent delta when started:
                    yield return delta.Data.DeltaContent;
                    break;
                case SessionErrorEvent error when started:
                    throw new InvalidOperationException($"Session error: {error.Data.Message}");
                case SessionIdleEvent when started:
                    yield break;
            }
        }
    }

    public async Task<TicketTriage> TriageAsync(TriageRequest request, CancellationToken cancellationToken)
    {
        await using var session = await OpenAsync(conversationId: null, streaming: false, cancellationToken);
        try
        {
            return await session.SendAndWaitAsync<TicketTriage>(
                $"Triage this support ticket.\nSubject: {request.Subject}\n\n{request.Body}", timeout: s_timeout, cancellationToken: cancellationToken);
        }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
            await session.AbortAsync(CancellationToken.None);
            throw;
        }
    }

    private async Task<CopilotSession> OpenAsync(string? conversationId, bool streaming, CancellationToken cancellationToken) =>
        conversationId is null
            ? await _copilot.CreateSessionAsync(Configure(new SessionConfig(), streaming), cancellationToken)
            : await _copilot.ResumeSessionAsync(conversationId, Configure(new ResumeSessionConfig(), streaming), cancellationToken);

    private T Configure<T>(T config, bool streaming) where T : SessionConfigBase
    {
        config.Model = _model;
        config.SystemMessage = new SystemMessageConfig { Mode = SystemMessageMode.Append, Content = Instructions };
        config.Tools = [.. _tools];
        config.AvailableTools = [.. _tools.Select(tool => $"custom:{tool.Name}")]; // Empty mode: offer only our tools
        config.OnPermissionRequest = DecideAsync;
        config.Streaming = streaming;
        return config;
    }

    // Our own tools run; refunds under $100 are approved; everything else is denied.
    private static Task<PermissionDecision> DecideAsync(PermissionRequest request, PermissionInvocation invocation) =>
        Task.FromResult(request switch
        {
            PermissionRequestCustomTool { ToolName: "lookup_order" } => PermissionDecision.ApproveOnce(),
            PermissionRequestCustomTool { ToolName: "refund_order", Args: JsonElement args }
                when args.GetProperty("amount").GetDecimal() < 100 => PermissionDecision.ApproveOnce(),
            _ => PermissionDecision.Reject("Denied by the application's permission policy."),
        });

    // Tools run on the SDK's thread, outside any request scope: create one per call, and don't leak exception details.
    private T InScope<T>(Func<IOrderRepository, T> action)
    {
        using var scope = _scopes.CreateScope();
        try
        {
            return action(scope.ServiceProvider.GetRequiredService<IOrderRepository>());
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Tool call failed.");
            throw new InvalidOperationException("The tool failed.");
        }
    }

    private async Task<IAsyncDisposable> LockAsync(string? conversationId, CancellationToken cancellationToken)
    {
        if (conversationId is null)
        {
            return new Release(null);
        }

        var gate = _conversationLocks.GetOrAdd(conversationId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancellationToken);
        return new Release(gate);
    }

    private sealed class Release(SemaphoreSlim? gate) : IAsyncDisposable
    {
        public ValueTask DisposeAsync()
        {
            gate?.Release();
            return ValueTask.CompletedTask;
        }
    }
}
