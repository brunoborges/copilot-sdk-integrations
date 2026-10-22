using Aspire.Hosting;
using Aspire.Hosting.ApplicationModel;
using static CopilotSdk.Aspire.Hosting.Tests.TestHelpers;

namespace CopilotSdk.Aspire.Hosting.Tests;

public class ContainerAndExistingTests
{
    [Fact]
    public async Task RunAsContainer_SwapsInContainerWithSameNameAndDockerfile()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-github-token"] = "gh-secret";

        // A non-loopback collector: rewriting localhost for containers needs a running orchestrator.
        builder.Configuration["ASPIRE_DASHBOARD_OTLP_HTTP_ENDPOINT_URL"] = "http://otel.test:18890";
        var copilot = builder.AddCopilot("copilot", port: 4321)
            .WithEnvironment("BEFORE", "1")
            .RunAsContainer()
            .WithEnvironment("AFTER", "2");

        var runtime = copilot.Resource;
        var container = Assert.IsType<CopilotContainerResource>(runtime.InnerResource);
        Assert.Same(container, runtime.Container);
        Assert.DoesNotContain(runtime, builder.Resources);
        Assert.Contains(container, builder.Resources);
        Assert.Equal("copilot", container.Name);

        var endpoint = Assert.Single(container.Annotations.OfType<EndpointAnnotation>());
        Assert.Equal("tcp", endpoint.Name);
        Assert.Equal(4321, endpoint.Port);
        Assert.Equal(CopilotContainerResource.RuntimePort, endpoint.TargetPort);

        Assert.Single(container.Annotations.OfType<DockerfileBuildAnnotation>());
        Assert.Contains(container.Annotations.OfType<HealthCheckAnnotation>(), h => h.Key == "copilot_copilot_check");
        Assert.DoesNotContain(container.Annotations, a => a is ExecutableAnnotation);

        var github = Assert.Single(builder.Resources.OfType<ParameterResource>(), p => p.Name == "copilot-github-token");
        Assert.True(github.Secret);
        Assert.Same(github, runtime.GitHubTokenParameter);

        Allocate(container, "tcp", port: 4321);
        var config = await EvaluateAsync(container);
        var env = config.EnvironmentVariables.ToDictionary();
        Assert.Equal("1", env["BEFORE"]);
        Assert.Equal("2", env["AFTER"]);
        Assert.Equal(CopilotContainerResource.HomeDirectory, env["COPILOT_HOME"]);
        Assert.Equal("gh-secret", env["COPILOT_GITHUB_TOKEN"]);
        Assert.Equal("true", env["COPILOT_OTEL_ENABLED"]);
        Assert.Equal(["--headless", "--no-auto-update", "--host", "0.0.0.0", "--port", "3000"], config.Arguments.Select(a => a.Value));

        var connectionString = await runtime.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken);
        Assert.StartsWith("Endpoint=tcp://localhost:4321;ConnectionToken=", connectionString, StringComparison.Ordinal);
    }

    [Fact]
    public void RunAsContainer_WithImageReplacesDockerfile()
    {
        var builder = CreateBuilder();

        var runtime = builder.AddCopilot("copilot")
            .RunAsContainer(c => c.WithImage("myregistry.azurecr.io/copilot-runtime", "1.0.103"))
            .Resource;

        var container = runtime.Container!;
        Assert.Empty(container.Annotations.OfType<DockerfileBuildAnnotation>());
        var image = Assert.Single(container.Annotations.OfType<ContainerImageAnnotation>());
        Assert.Equal("myregistry.azurecr.io/copilot-runtime", image.Image);
        Assert.Equal("1.0.103", image.Tag);
    }

    [Fact]
    public void RunAsContainer_CalledTwiceConfiguresTheSameContainer()
    {
        var builder = CreateBuilder();
        var copilot = builder.AddCopilot("copilot").RunAsContainer();
        var container = copilot.Resource.Container;

        copilot.RunAsContainer(c => c.WithLifetime(ContainerLifetime.Persistent));

        Assert.Same(container, copilot.Resource.Container);
        Assert.Single(builder.Resources.OfType<CopilotContainerResource>());
        Assert.Contains(container!.Annotations, a => a is ContainerLifetimeAnnotation { Lifetime: ContainerLifetime.Persistent });
    }

    [Fact]
    public void WithGitHubToken_RemovesGeneratedParameter()
    {
        var builder = CreateBuilder();
        var token = builder.AddParameter("gh", secret: true);

        var runtime = builder.AddCopilot("copilot").RunAsContainer().WithGitHubToken(token).Resource;

        Assert.DoesNotContain(builder.Resources, r => r.Name == "copilot-github-token");
        Assert.Same(token.Resource, runtime.GitHubTokenParameter);
    }

    [Fact]
    public void WithDataVolume_MountsHomeInContainer()
    {
        var builder = CreateBuilder();

        var runtime = builder.AddCopilot("copilot").WithDataVolume().RunAsContainer().Resource;

        var mount = Assert.Single(runtime.Container!.Annotations.OfType<ContainerMountAnnotation>());
        Assert.Equal(ContainerMountType.Volume, mount.Type);
        Assert.Equal(CopilotContainerResource.HomeDirectory, mount.Target);
        Assert.False(string.IsNullOrEmpty(mount.Source));
    }

    [Fact]
    public void Dockerfile_PinsCliVersionAndVerifiesChecksum()
    {
        var dockerfile = CopilotDockerfile.Create(CopilotRuntimeLocator.CliVersion);

        Assert.Matches(@"^\d+\.\d+\.\d+", CopilotRuntimeLocator.CliVersion);
        Assert.Contains($"ARG COPILOT_VERSION={CopilotRuntimeLocator.CliVersion}", dockerfile, StringComparison.Ordinal);
        Assert.Contains("sha256sum -c", dockerfile, StringComparison.Ordinal);
        Assert.Contains("ENTRYPOINT [\"/opt/copilot/copilot-runtime\"]", dockerfile, StringComparison.Ordinal);
        Assert.Contains("EXPOSE 3000", dockerfile, StringComparison.Ordinal);
        Assert.DoesNotContain("/tmp", dockerfile, StringComparison.Ordinal);
    }

    [Fact]
    public async Task PublishMode_RunsAsContainerWithInternalEndpoint()
    {
        var builder = CreatePublishBuilder();

        var runtime = builder.AddCopilot("copilot").Resource;

        var container = Assert.IsType<CopilotContainerResource>(runtime.InnerResource);
        var endpoint = Assert.Single(container.Annotations.OfType<EndpointAnnotation>());
        Assert.False(endpoint.IsExternal);
        Assert.Contains(builder.Resources, r => r.Name == "copilot-github-token");

        var manifest = await GetContainerManifestAsync(container);
        var node = manifest;
        Assert.Equal("Endpoint=tcp://{copilot.bindings.tcp.host}:{copilot.bindings.tcp.port};ConnectionToken={copilot-connection-token.value}", node["connectionString"]?.GetValue<string>());
        Assert.Equal("{copilot-github-token.value}", node["env"]?["COPILOT_GITHUB_TOKEN"]?.GetValue<string>());
        Assert.Equal("{copilot-connection-token.value}", node["env"]?["COPILOT_CONNECTION_TOKEN"]?.GetValue<string>());
        Assert.Null(node["env"]?["COPILOT_OTEL_ENABLED"]);
        Assert.NotNull(node["build"]);
    }

    [Fact]
    public async Task AsExisting_UsesParametersAndStartsNothing()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:endpoint"] = "tcp://copilot.internal:3000";
        builder.Configuration["Parameters:token"] = "s3cret";
        var endpoint = builder.AddParameter("endpoint");
        var token = builder.AddParameter("token", secret: true);

        var copilot = builder.AddCopilot("copilot").AsExisting(endpoint, token);
        var chat = copilot.AddModel("chat", "gpt-5.4");

        var runtime = copilot.Resource;
        Assert.True(runtime.IsExisting);
        Assert.IsType<ConnectionStringResource>(runtime.InnerResource);
        Assert.DoesNotContain(builder.Resources, r => r is ExecutableResource or ContainerResource);
        Assert.Same(token.Resource, runtime.ConnectionTokenParameter);

        Assert.Equal("Endpoint=tcp://copilot.internal:3000;ConnectionToken=s3cret", await runtime.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Equal("Endpoint=tcp://copilot.internal:3000;ConnectionToken=s3cret;Model=gpt-5.4", await chat.Resource.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Throws<InvalidOperationException>(() => copilot.RunAsContainer());
    }

    [Fact]
    public void AsExisting_InPublishModeReplacesContainer()
    {
        var builder = CreatePublishBuilder();
        var endpoint = builder.AddParameter("endpoint");
        var token = builder.AddParameter("token", secret: true);

        var runtime = builder.AddCopilot("copilot").AsExisting(endpoint, token).Resource;

        Assert.IsType<ConnectionStringResource>(runtime.InnerResource);
        Assert.Empty(builder.Resources.OfType<CopilotContainerResource>());
        Assert.DoesNotContain(builder.Resources, r => r.Name == "copilot-github-token");
    }
}
