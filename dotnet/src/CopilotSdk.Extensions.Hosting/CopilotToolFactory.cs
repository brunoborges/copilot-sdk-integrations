using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.RegularExpressions;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>An app tool built for an agent.</summary>
/// <param name="Function">The function added to <see cref="SessionConfigBase.Tools"/>.</param>
/// <param name="RequiresApproval">Whether the tool is marked with <see cref="RequiresApprovalAttribute"/>.</param>
internal sealed record CopilotToolDefinition(AIFunction Function, bool RequiresApproval)
{
    public string Name => Function.Name;
}

/// <summary>
/// Builds <see cref="AIFunction"/>s for registered tools: discovery of <c>[Description]</c> methods, snake_case
/// names, DI scope per call, caller <see cref="ExecutionContext"/> flow, <c>SkipPermission</c>, and generic
/// error results.
/// </summary>
internal sealed partial class CopilotToolFactory
{
    /// <summary>Logger category for tool failures.</summary>
    public const string LoggerCategory = "CopilotSdk.Tools";

    // Mirrors the SDK's (internal) CopilotTool.SkipPermissionKey; see CopilotToolOptions.SkipPermission.
    internal const string SkipPermissionKey = "skip_permission";

    private const BindingFlags ToolMethodFlags = BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static;

    private readonly IServiceProvider _services;
    private readonly CopilotTurnContextRegistry? _turns;
    private readonly ILogger _logger;
    private readonly IServiceProviderIsService? _isService;
    private readonly IServiceProviderIsKeyedService? _isKeyedService;

    public CopilotToolFactory(IServiceProvider services, CopilotTurnContextRegistry? turns, ILoggerFactory? loggerFactory)
    {
        _services = services;
        _turns = turns;
        _logger = (loggerFactory ?? Microsoft.Extensions.Logging.Abstractions.NullLoggerFactory.Instance).CreateLogger(LoggerCategory);
        _isService = services.GetService<IServiceProviderIsService>();
        _isKeyedService = _isService as IServiceProviderIsKeyedService ?? services.GetService<IServiceProviderIsKeyedService>();
    }

    /// <summary>Builds the tools for one registration.</summary>
    public IEnumerable<CopilotToolDefinition> Create(CopilotToolRegistration registration)
    {
        if (registration.ToolType is { } toolType)
        {
            foreach (var method in GetToolMethods(toolType))
            {
                yield return CreateForMethod(toolType, method);
            }
        }
        else if (registration.Function is { } function)
        {
            yield return CreateForDelegate(function, registration.Name, registration.Description);
        }
    }

    /// <summary>Public instance and static methods of <paramref name="toolType"/> marked with <see cref="DescriptionAttribute"/>.</summary>
    public static IReadOnlyList<MethodInfo> GetToolMethods(Type toolType) =>
        [.. toolType.GetMethods(ToolMethodFlags)
            .Where(m => !m.IsSpecialName && m.DeclaringType != typeof(object) && m.IsDefined(typeof(DescriptionAttribute), inherit: true))
            .OrderBy(m => m.MetadataToken)];

    /// <summary>
    /// The tool name for <paramref name="method"/>: an explicit <see cref="DisplayNameAttribute"/> wins; otherwise the snake_case method name without an <c>Async</c> suffix.
    /// Returns <see langword="null"/> for a compiler-generated lambda without a name.
    /// </summary>
    public static string? GetToolName(MethodInfo method)
    {
        if (method.GetCustomAttribute<DisplayNameAttribute>(inherit: true)?.DisplayName is { Length: > 0 } displayName)
        {
            return displayName;
        }

        var name = method.Name;
        if (name.Contains('<', StringComparison.Ordinal))
        {
            // Local functions compile to "<Outer>g__Name|0_0"; lambdas ("<Outer>b__0_0") have no usable name.
            var match = LocalFunctionName().Match(name);
            if (!match.Success)
            {
                return null;
            }

            name = match.Groups[1].Value;
        }

        return ToSnakeCase(name);
    }

    /// <summary>Converts a method name to a tool name: <c>LookupOrderAsync</c> → <c>lookup_order</c>.</summary>
    public static string ToSnakeCase(string methodName)
    {
        const string AsyncSuffix = "Async";
        if (methodName.Length > AsyncSuffix.Length && methodName.EndsWith(AsyncSuffix, StringComparison.Ordinal))
        {
            methodName = methodName[..^AsyncSuffix.Length];
        }

        return JsonNamingPolicy.SnakeCaseLower.ConvertName(methodName);
    }

    private CopilotToolDefinition CreateForMethod(Type toolType, MethodInfo method)
    {
        if (method.ContainsGenericParameters)
        {
            throw new InvalidOperationException(
                $"Tool method '{toolType.Name}.{method.Name}' is generic. Tool methods can't have generic type parameters.");
        }

        var requiresApproval = method.IsDefined(typeof(RequiresApprovalAttribute), inherit: true);
        var options = CreateFactoryOptions(GetToolName(method)!, description: null, requiresApproval);

        AIFunction declaration;
        Func<AIFunctionArguments, AIFunction> resolve;
        if (method.IsStatic)
        {
            declaration = AIFunctionFactory.Create(method, target: null, options);
            resolve = _ => declaration;
        }
        else
        {
            // The declaration provides the name and schema; each call binds the method to the instance resolved
            // from the call's scope. The descriptor (schema, marshallers) is cached by AIFunctionFactory, and
            // binding a target this way leaves its disposal to the scope.
            declaration = AIFunctionFactory.Create(method, static _ => throw new UnreachableException(), options);
            resolve = arguments => AIFunctionFactory.Create(method, arguments.Services!.GetRequiredService(toolType), options);
        }

        return new CopilotToolDefinition(new CopilotToolFunction(this, declaration, resolve), requiresApproval);
    }

    private CopilotToolDefinition CreateForDelegate(Delegate function, string? name, string? description)
    {
        var requiresApproval = function.Method.IsDefined(typeof(RequiresApprovalAttribute), inherit: true);
        var toolName = string.IsNullOrWhiteSpace(name) ? GetToolName(function.Method) : name;
        if (toolName is null)
        {
            throw new InvalidOperationException(
                "A tool registered with WithTool(delegate) needs a name. Pass the name argument or use a named method.");
        }

        var options = CreateFactoryOptions(toolName, description, requiresApproval);
        var declaration = AIFunctionFactory.Create(function, options);
        return new CopilotToolDefinition(new CopilotToolFunction(this, declaration, _ => declaration), requiresApproval);
    }

    private AIFunctionFactoryOptions CreateFactoryOptions(string name, string? description, bool requiresApproval) => new()
    {
        Name = name,
        Description = description,
        ConfigureParameterBinding = BindParameter,
        AdditionalProperties = requiresApproval
            ? null
            : new Dictionary<string, object?>(StringComparer.Ordinal) { [SkipPermissionKey] = true },
    };

    private AIFunctionFactoryOptions.ParameterBindingOptions BindParameter(ParameterInfo parameter)
    {
        var type = parameter.ParameterType;

        if (type == typeof(ToolInvocation))
        {
            return new()
            {
                ExcludeFromSchema = true,
                BindParameter = static (pi, arguments) =>
                    arguments.Context is not null && arguments.Context.TryGetValue(typeof(ToolInvocation), out var invocation)
                        ? invocation
                        : pi.HasDefaultValue ? null : throw new InvalidOperationException($"No {nameof(ToolInvocation)} was provided for the tool call."),
            };
        }

        if (type == typeof(CancellationToken) || type == typeof(IServiceProvider) || type == typeof(AIFunctionArguments))
        {
            return default;
        }

        if (parameter.GetCustomAttribute<FromKeyedServicesAttribute>() is { } keyed)
        {
            return new()
            {
                ExcludeFromSchema = true,
                BindParameter = (pi, arguments) =>
                {
                    var services = GetCallServices(arguments);
                    return pi.HasDefaultValue
                        ? services.GetKeyedServices(type, keyed.Key).LastOrDefault() ?? pi.DefaultValue
                        : services.GetRequiredKeyedService(type, keyed.Key);
                },
            };
        }

        if (IsServiceParameter(parameter))
        {
            return new()
            {
                ExcludeFromSchema = true,
                BindParameter = (pi, arguments) =>
                {
                    var services = GetCallServices(arguments);
                    return pi.HasDefaultValue
                        ? services.GetService(type) ?? pi.DefaultValue
                        : services.GetRequiredService(type);
                },
            };
        }

        return default;
    }

    private bool IsServiceParameter(ParameterInfo parameter)
    {
        // [FromServices] from ASP.NET Core (or any framework), matched by name to avoid the dependency.
        if (parameter.GetCustomAttributes(inherit: true).Any(a => a.GetType().Name == "FromServicesAttribute"))
        {
            return true;
        }

        var type = parameter.ParameterType;
        if (type.IsPrimitive || type.IsEnum || type == typeof(string) || type == typeof(decimal) || type == typeof(object))
        {
            return false;
        }

        return _isService?.IsService(type) == true;
    }

    private static IServiceProvider GetCallServices(AIFunctionArguments arguments) =>
        arguments.Services ?? throw new InvalidOperationException("No service provider was supplied for the tool call.");

    /// <summary>
    /// Runs <paramref name="callback"/> for the turn that owns <paramref name="sessionId"/>: inside the caller's
    /// <see cref="ExecutionContext"/>, with the turn's service provider and cancellation token. Without a turn
    /// (for example, a direct invocation), it runs with the root provider in the current context.
    /// </summary>
    internal static Task<T> RunInTurnAsync<T>(
        CopilotTurnContextRegistry? turns,
        IServiceProvider rootServices,
        string? sessionId,
        Func<IServiceProvider, CancellationToken, Task<T>> callback,
        CancellationToken cancellationToken)
    {
        TurnContext? turn = null;
        if (sessionId is not null && turns?.TryGet(sessionId, out var found) == true)
        {
            turn = found;
        }

        var services = turn?.Services ?? rootServices;
        if (turn?.ExecutionContext is not { } executionContext)
        {
            return Run(callback, services, cancellationToken, turn?.CancellationToken ?? default);
        }

        // Start the callback inside the caller's context; its continuations keep that context.
        Task<T>? task = null;
        ExecutionContext.Run(
            executionContext,
            _ => task = Run(callback, services, cancellationToken, turn.CancellationToken),
            null);
        return task!;

        static async Task<T> Run(
            Func<IServiceProvider, CancellationToken, Task<T>> callback,
            IServiceProvider services,
            CancellationToken cancellationToken,
            CancellationToken turnCancellationToken)
        {
            if (!turnCancellationToken.CanBeCanceled || turnCancellationToken == cancellationToken)
            {
                return await callback(services, cancellationToken).ConfigureAwait(false);
            }

            using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, turnCancellationToken);
            return await callback(services, linked.Token).ConfigureAwait(false);
        }
    }

    [GeneratedRegex(@"^<[^>]*>g__([A-Za-z_][A-Za-z0-9_]*)\|")]
    private static partial Regex LocalFunctionName();

    /// <summary>
    /// The function given to the SDK. Each call opens a DI scope from the turn's services, resolves the tool
    /// from it, runs in the caller's <see cref="ExecutionContext"/>, and turns exceptions into a generic failure.
    /// </summary>
    private sealed class CopilotToolFunction(
        CopilotToolFactory factory,
        AIFunction declaration,
        Func<AIFunctionArguments, AIFunction> resolve) : DelegatingAIFunction(declaration)
    {
        protected override async ValueTask<object?> InvokeCoreAsync(AIFunctionArguments arguments, CancellationToken cancellationToken)
        {
            var invocation = arguments.Context is not null && arguments.Context.TryGetValue(typeof(ToolInvocation), out var value)
                ? value as ToolInvocation
                : null;

            try
            {
                return await RunInTurnAsync(
                    factory._turns,
                    factory._services,
                    invocation?.SessionId,
                    async (services, ct) =>
                    {
                        await using var scope = services.CreateAsyncScope();
                        var callArguments = new AIFunctionArguments(arguments)
                        {
                            Services = scope.ServiceProvider,
                            Context = arguments.Context,
                        };

                        return await resolve(callArguments).InvokeAsync(callArguments, ct).ConfigureAwait(false);
                    },
                    cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException ex) when (cancellationToken.IsCancellationRequested || ex.CancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                LogToolFailed(factory._logger, ex, Name, invocation?.ToolCallId, invocation?.SessionId);

                var message = $"Tool '{Name}' failed.";
                return new ToolResultAIContent(new ToolResultObject
                {
                    ResultType = "failure",
                    TextResultForLlm = message,
                    Error = message,
                });
            }
        }
    }

    [LoggerMessage(EventId = 1, Level = LogLevel.Error, Message = "Tool '{ToolName}' failed (tool call {ToolCallId}, session {SessionId}).")]
    private static partial void LogToolFailed(ILogger logger, Exception exception, string toolName, string? toolCallId, string? sessionId);
}
