using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class ConnectionStringTests
{
    [Fact]
    public void TryParse_ReadsAllKeys_CaseInsensitive()
    {
        Assert.True(CopilotConnectionString.TryParse(
            "endpoint=tcp://localhost:5000;CONNECTIONTOKEN=secret;Model=gpt-5.4;provider=azure;" +
            "ProviderEndpoint=https://aoai/;ProviderKey=k;WireApi=responses;McpServers=docs, fs",
            out var info));

        Assert.Equal("tcp://localhost:5000", info.Endpoint);
        Assert.Equal("secret", info.ConnectionToken);
        Assert.Equal("gpt-5.4", info.Model);
        Assert.Equal("azure", info.Provider);
        Assert.Equal("https://aoai/", info.ProviderEndpoint);
        Assert.Equal("k", info.ProviderKey);
        Assert.Equal("responses", info.WireApi);
        Assert.Equal(["docs", "fs"], info.McpServers);
        Assert.Empty(info.UnknownKeys);
    }

    [Fact]
    public void TryParse_ReportsUnknownKeys()
    {
        Assert.True(CopilotConnectionString.TryParse("Endpoint=tcp://h:1;Future=1", out var info));

        Assert.Equal("tcp://h:1", info.Endpoint);
        Assert.Equal(["future"], info.UnknownKeys.Select(k => k.ToLowerInvariant()));
    }

    [Fact]
    public void TryParse_AcceptsBareUri()
    {
        Assert.True(CopilotConnectionString.TryParse("tcp://localhost:5000", out var info));
        Assert.Equal("tcp://localhost:5000", info.Endpoint);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not a connection string")]
    [InlineData("Endpoint=\"unterminated")]
    public void TryParse_RejectsInvalid(string? value)
    {
        Assert.False(CopilotConnectionString.TryParse(value, out _));
    }

    [Fact]
    public void ConnectionString_FillsUnsetValues()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new() { ["ConnectionStrings:copilot"] = "Endpoint=tcp://runtime:5000;ConnectionToken=secret;Model=gpt-5.4" });

        var o = sp.GetOptions();

        Assert.Equal("tcp://runtime:5000", o.Endpoint);
        Assert.Equal("secret", o.ConnectionToken);
        Assert.Equal("gpt-5.4", o.Model);
    }

    [Fact]
    public void ExplicitValues_Win()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new()
            {
                ["ConnectionStrings:copilot"] = "Endpoint=tcp://runtime:5000;ConnectionToken=secret;Model=gpt-5.4",
                ["Copilot:Endpoint"] = "tcp://localhost:6000",
                ["Copilot:Model"] = "explicit",
            });

        var o = sp.GetOptions();

        Assert.Equal("tcp://localhost:6000", o.Endpoint);
        Assert.Null(o.ConnectionToken); // never pair an explicit endpoint with another runtime's token
        Assert.Equal("explicit", o.Model);
    }

    [Fact]
    public void ConnectionName_SelectsAnotherConnectionString()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(o => o.ConnectionName = "agents"),
            new()
            {
                ["ConnectionStrings:copilot"] = "Endpoint=tcp://wrong:1",
                ["ConnectionStrings:agents"] = "Endpoint=tcp://right:2;ConnectionToken=t",
            });

        Assert.Equal("tcp://right:2", sp.GetOptions().Endpoint);
    }

    [Fact]
    public void ConnectionName_FromConfiguration()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new()
            {
                ["Copilot:ConnectionName"] = "agents",
                ["ConnectionStrings:agents"] = "Endpoint=tcp://right:2;ConnectionToken=t",
            });

        Assert.Equal("tcp://right:2", sp.GetOptions().Endpoint);
    }

    [Fact]
    public void NamedAgent_InheritsModel_UnlessSet()
    {
        using var sp = TestHost.Build(
            s => { s.AddCopilot(); s.AddCopilot("a"); s.AddCopilot("b"); },
            new()
            {
                ["ConnectionStrings:copilot"] = "Endpoint=tcp://runtime:5000;ConnectionToken=t;Model=cs-model",
                ["Copilot:Agents:b:Model"] = "b-model",
            });

        Assert.Equal("cs-model", sp.GetOptions("a").Model);
        Assert.Equal("b-model", sp.GetOptions("b").Model);
    }

    [Fact]
    public void Provider_FromConnectionString()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new() { ["ConnectionStrings:copilot"] = "Model=m;Provider=azure;ProviderEndpoint=https://aoai/;ProviderKey=k;WireApi=responses" });

        var p = sp.GetOptions().Provider;

        Assert.NotNull(p);
        Assert.Equal(("azure", "https://aoai/", "k", "responses"), (p.Type, p.BaseUrl, p.ApiKey, p.WireApi));
        Assert.Null(sp.GetOptions().Endpoint);
    }

    [Fact]
    public void Provider_ExplicitWins()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new()
            {
                ["ConnectionStrings:copilot"] = "Provider=azure;ProviderEndpoint=https://aoai/",
                ["Copilot:Provider:Type"] = "openai",
                ["Copilot:Provider:BaseUrl"] = "https://openai/",
            });

        Assert.Equal("openai", sp.GetOptions().Provider!.Type);
    }

    [Fact]
    public void InvalidConnectionString_Throws()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new() { ["ConnectionStrings:copilot"] = "garbage" });

        var ex = Assert.Throws<InvalidOperationException>(() => sp.GetOptions());
        Assert.Contains("ConnectionStrings:copilot", ex.Message);
    }

    [Fact]
    public void ConnectionString_EndpointIgnored_WhenInProcess()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(o => o.InProcess = true),
            new() { ["ConnectionStrings:copilot"] = "Endpoint=tcp://runtime:5000;ConnectionToken=secret;Model=gpt-5.4" });

        var o = sp.GetOptions();

        Assert.Null(o.Endpoint);
        Assert.Null(o.ConnectionToken);
        Assert.Equal("gpt-5.4", o.Model);
    }
}
