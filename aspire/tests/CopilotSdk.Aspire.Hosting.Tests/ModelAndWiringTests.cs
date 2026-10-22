using Aspire.Hosting;
using Aspire.Hosting.ApplicationModel;
using static CopilotSdk.Aspire.Hosting.Tests.TestHelpers;

namespace CopilotSdk.Aspire.Hosting.Tests;

public class ModelAndWiringTests
{
    [Fact]
    public async Task AddModel_AppendsModelToRuntimeConnectionString()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        var copilot = builder.AddCopilot("copilot");

        var chat = copilot.AddModel("chat", "gpt-5.4");

        Assert.Same(copilot.Resource, chat.Resource.Parent);
        Assert.Same(chat.Resource, Assert.Single(copilot.Resource.Models));
        Assert.Same(copilot.Resource, ((IResourceWithParent)chat.Resource).Parent);

        Allocate(copilot.Resource, "tcp", port: 5000);
        var value = await chat.Resource.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken);
        Assert.EndsWith(";Model=gpt-5.4", value, StringComparison.Ordinal);
        Assert.StartsWith("Endpoint=tcp://localhost:5000;ConnectionToken=", value, StringComparison.Ordinal);

        var properties = ((IResourceWithConnectionString)chat.Resource).GetConnectionProperties().ToDictionary(p => p.Key, p => p.Value);
        Assert.Equal("gpt-5.4", await properties["Model"].GetValueAsync(TestContext.Current.CancellationToken));
        Assert.Contains("Endpoint", properties.Keys);
    }

    [Fact]
    public void Model_ParentIsTheRunningResourceAfterSwap()
    {
        var builder = CreateBuilder();
        var copilot = builder.AddCopilot("copilot");
        var chat = copilot.AddModel("chat", "gpt-5.4");

        copilot.RunAsContainer();

        Assert.Same(copilot.Resource.Container, ((IResourceWithParent)chat.Resource).Parent);
    }

    [Fact]
    public async Task WithReference_ToModel_FlowsConnectionString()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        var copilot = builder.AddCopilot("copilot");
        var chat = copilot.AddModel("chat", "gpt-5.4");
        var api = builder.AddExecutable("api", "dotnet", ".").WithReference(chat);
        Allocate(copilot.Resource, "tcp", port: 5000);

        var env = await GetEnvironmentAsync(api.Resource);

        Assert.Equal("Endpoint=tcp://localhost:5000;ConnectionToken=s3cret;Model=gpt-5.4", env["ConnectionStrings__chat"]);
    }

    [Fact]
    public async Task WithReference_ToModel_FlowsLanguageNeutralConnectionProperties()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        var copilot = builder.AddCopilot("copilot");
        var chat = copilot.AddModel("chat", "gpt-5.4");
        var web = builder.AddExecutable("web", "node", ".").WithReference(chat);
        Allocate(copilot.Resource, "tcp", port: 5000);

        var env = await GetEnvironmentAsync(web.Resource);

        Assert.Equal("localhost", env["CHAT_HOST"]);
        Assert.Equal("5000", env["CHAT_PORT"]);
        Assert.Equal("tcp://localhost:5000", env["CHAT_ENDPOINT"]);
        Assert.Equal("s3cret", env["CHAT_CONNECTIONTOKEN"]);
        Assert.Equal("gpt-5.4", env["CHAT_MODEL"]);
    }

    [Fact]
    public async Task WithMcpServer_FlowsServerSettingsToReferencingResources()
    {
        var builder = CreateBuilder();
        var docs = builder.AddExecutable("docs-mcp", "dotnet", ".").WithHttpEndpoint(name: "http");
        var copilot = builder.AddCopilot("copilot").WithMcpServer(docs, tools: ["search_docs", "get_page"]);
        var chat = copilot.AddModel("chat", "gpt-5.4").WithReasoningEffort("high");
        var api = builder.AddExecutable("api", "dotnet", ".").WithReference(chat);
        var worker = builder.AddExecutable("worker", "dotnet", ".").WithReference(copilot);
        var other = builder.AddExecutable("other", "dotnet", ".");

        Assert.Contains(copilot.Resource.Annotations, a => a is WaitAnnotation w && w.Resource == docs.Resource);

        await using var app = builder.Build();
        await PublishBeforeStartAsync(builder, app);
        Allocate(copilot.Resource, "tcp", port: 5000, targetPort: 5001);
        Allocate(docs.Resource, "http", port: 7000);

        var apiEnv = await GetEnvironmentAsync(api.Resource);
        Assert.Equal("http", apiEnv["Copilot__McpServers__docs-mcp__Type"]);
        Assert.Equal("http://localhost:7000/mcp", apiEnv["Copilot__McpServers__docs-mcp__Url"]);
        Assert.Equal("search_docs", apiEnv["Copilot__McpServers__docs-mcp__Tools__0"]);
        Assert.Equal("get_page", apiEnv["Copilot__McpServers__docs-mcp__Tools__1"]);
        Assert.Equal("high", apiEnv["Copilot__ReasoningEffort"]);

        var workerEnv = await GetEnvironmentAsync(worker.Resource);
        Assert.Equal("http://localhost:7000/mcp", workerEnv["Copilot__McpServers__docs-mcp__Url"]);
        Assert.False(workerEnv.ContainsKey("Copilot__ReasoningEffort"));

        var otherEnv = await GetEnvironmentAsync(other.Resource);
        Assert.DoesNotContain(otherEnv.Keys, k => k.StartsWith("Copilot__", StringComparison.Ordinal));

        var runtimeEnv = await GetEnvironmentAsync(copilot.Resource);
        Assert.DoesNotContain(runtimeEnv.Keys, k => k.StartsWith("Copilot__", StringComparison.Ordinal));
    }

    [Fact]
    public void WithMcpServer_RequiresAnEndpoint()
    {
        var builder = CreateBuilder();
        var docs = builder.AddExecutable("docs-mcp", "dotnet", ".");

        Assert.Throws<InvalidOperationException>(() => builder.AddCopilot("copilot").WithMcpServer(docs));
    }

    [Fact]
    public async Task WithProvider_ReadsEndpointAndKeyFromConnectionString()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        builder.Configuration["ConnectionStrings:openai"] = "Endpoint=https://api.openai.com/v1;Key=sk-123";
        var openai = builder.AddConnectionString("openai");
        var copilot = builder.AddCopilot("copilot");
        var chat = copilot.AddModel("chat", "gpt-5.4").WithProvider(openai, CopilotWireApi.Responses);
        Allocate(copilot.Resource, "tcp", port: 5000);

        var value = await chat.Resource.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken);

        Assert.Equal(
            "Endpoint=tcp://localhost:5000;ConnectionToken=s3cret;Model=gpt-5.4;Provider=openai;ProviderEndpoint=https://api.openai.com/v1;ProviderKey=sk-123;WireApi=responses",
            value);
        Assert.Equal("openai", chat.Resource.Provider!.Type);
        Assert.Contains(chat.Resource.Annotations, a => a is ResourceRelationshipAnnotation r && r.Resource == openai.Resource);
    }

    [Fact]
    public async Task WithProvider_AcceptsBareUrlAndExplicitType()
    {
        var builder = CreateBuilder();
        builder.Configuration["Parameters:copilot-connection-token"] = "s3cret";
        builder.Configuration["ConnectionStrings:ollama"] = "http://localhost:11434/v1";
        var ollama = builder.AddConnectionString("ollama");
        var copilot = builder.AddCopilot("copilot");
        var chat = copilot.AddModel("chat", "llama3").WithProvider(ollama, type: "openai");
        Allocate(copilot.Resource, "tcp", port: 5000);

        var value = await chat.Resource.ConnectionStringExpression.GetValueAsync(TestContext.Current.CancellationToken);

        Assert.EndsWith(";Model=llama3;Provider=openai;ProviderEndpoint=http://localhost:11434/v1;ProviderKey=", value, StringComparison.Ordinal);
    }

    [Fact]
    public void InferProviderType_DetectsAzureResources()
    {
        Assert.Equal("azure", CopilotResourceBuilderExtensions.InferProviderType(new AzureOpenAIDeploymentFake("gpt")));
        Assert.Equal("openai", CopilotResourceBuilderExtensions.InferProviderType(new ConnectionStringResource("x", ReferenceExpression.Create($"y"))));
    }

    private sealed class AzureOpenAIDeploymentFake(string name) : Resource(name);
}
