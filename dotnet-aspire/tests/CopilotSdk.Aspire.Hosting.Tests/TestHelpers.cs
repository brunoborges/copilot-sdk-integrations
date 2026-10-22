using System.Runtime.ExceptionServices;
using Aspire.Hosting;
using Aspire.Hosting.ApplicationModel;
using Aspire.Hosting.Publishing;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace CopilotSdk.Aspire.Hosting.Tests;

internal static class TestHelpers
{
    public const string OtlpHttpEndpoint = "http://localhost:18890";

    public static IDistributedApplicationBuilder CreateBuilder(params string[] args)
    {
        var builder = DistributedApplication.CreateBuilder(new DistributedApplicationOptions
        {
            Args = args,
            DisableDashboard = true,
            AssemblyName = typeof(TestHelpers).Assembly.GetName().Name,
        });
        builder.Configuration["ASPIRE_DASHBOARD_OTLP_HTTP_ENDPOINT_URL"] = OtlpHttpEndpoint;

        // BeforeStartEvent handlers validate DCP options; the tests never start DCP.
        builder.Configuration["DcpPublisher:CliPath"] = "dcp-not-used";
        builder.Configuration["DcpPublisher:DashboardPath"] = "dashboard-not-used";

        // Generated parameters resolve only when the app starts; give the default runtime one a value.
        builder.Configuration["Parameters:copilot-connection-token"] = "generated-token";
        return builder;
    }

    public static IDistributedApplicationBuilder CreatePublishBuilder() =>
        CreateBuilder("--publisher", "manifest", "--output-path", Path.Combine(AppContext.BaseDirectory, "manifest-out"));

    public static void Allocate(IResource resource, string endpointName, int port, int? targetPort = null, string host = "localhost")
    {
        var endpoint = resource.Annotations.OfType<EndpointAnnotation>().Single(e => e.Name == endpointName);
        if (targetPort is not null)
        {
            endpoint.TargetPort = targetPort;
        }

        endpoint.AllocatedEndpoint = new AllocatedEndpoint(endpoint, host, port);
    }

    public static async Task<IExecutionConfigurationResult> EvaluateAsync(
        IResource resource,
        DistributedApplicationOperation operation = DistributedApplicationOperation.Run,
        IServiceProvider? services = null,
        bool includeArguments = true)
    {
        var executionContext = new DistributedApplicationExecutionContext(new DistributedApplicationExecutionContextOptions(operation)
        {
            Services = services ?? new ServiceCollection().BuildServiceProvider(),
        });

        // Arguments reference the allocated target port and wait until it is allocated.
        var configuration = ExecutionConfigurationBuilder.Create(resource).WithEnvironmentVariablesConfig();
        if (includeArguments)
        {
            configuration = configuration.WithArgumentsConfig();
        }

        var result = await configuration.BuildAsync(executionContext, NullLogger.Instance, TestContext.Current.CancellationToken);

        if (result.Exception is not null)
        {
            ExceptionDispatchInfo.Throw(result.Exception);
        }

        return result;
    }

    public static async Task<Dictionary<string, string>> GetEnvironmentAsync(IResource resource, DistributedApplicationOperation operation = DistributedApplicationOperation.Run) =>
        (await EvaluateAsync(resource, operation, includeArguments: false)).EnvironmentVariables.ToDictionary();

    public static async Task<List<string>> GetArgumentsAsync(IResource resource, DistributedApplicationOperation operation = DistributedApplicationOperation.Run) =>
        (await EvaluateAsync(resource, operation)).Arguments.Select(a => a.Value).ToList();

    public static async Task<JsonNode> GetContainerManifestAsync(ContainerResource container)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
        {
            var options = new DistributedApplicationExecutionContextOptions(DistributedApplicationOperation.Publish)
            {
                Services = new ServiceCollection().BuildServiceProvider(),
            };
            var outputDirectory = Path.Combine(AppContext.BaseDirectory, "manifest-out");
            Directory.CreateDirectory(outputDirectory);
            var context = new ManifestPublishingContext(
                new DistributedApplicationExecutionContext(options),
                Path.Combine(outputDirectory, "manifest.json"),
                writer,
                TestContext.Current.CancellationToken);
            writer.WriteStartObject();
            await context.WriteContainerAsync(container);
            writer.WriteEndObject();
        }

        stream.Position = 0;
        return JsonNode.Parse(stream)!;
    }

    public static async Task PublishBeforeStartAsync(IDistributedApplicationBuilder builder, DistributedApplication app)
    {
        var model = app.Services.GetRequiredService<DistributedApplicationModel>();
        await builder.Eventing.PublishAsync(new BeforeStartEvent(app.Services, model), TestContext.Current.CancellationToken);
    }
}
