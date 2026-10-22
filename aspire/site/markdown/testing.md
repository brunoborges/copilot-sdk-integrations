---
title: Testing
---

# Testing

There are three levels of tests for a system that uses the Copilot runtime resource:

| Level | What it covers | Tooling |
| --- | --- | --- |
| AppHost model | The resources, connection strings, and wiring that the AppHost declares. Nothing starts. | xUnit (or any .NET test framework) and `Aspire.Hosting` |
| Services | Each service's Copilot code, against a fake runtime. | The test support of each language's integration |
| End to end | The whole distributed application, with a real runtime. | `Aspire.Hosting.Testing` |

## AppHost model tests

Build the application model and assert on the resources. Connection strings are reference
expressions, so you can check their shape without starting anything:

```cs
using Aspire.Hosting;

[Fact]
public void Chat_model_uses_the_runtime()
{
    var builder = DistributedApplication.CreateBuilder();
    var chat = builder.AddCopilot("copilot").AddModel("chat", "gpt-5.4");

    Assert.Equal("gpt-5.4", chat.Resource.Model);
    Assert.EndsWith(";Model=gpt-5.4", chat.Resource.ConnectionStringExpression.ValueExpression);
}
```

Generated parameters such as the connection token resolve only when the application starts. To give
them a value in a test, set configuration:

```cs
builder.Configuration["Parameters:copilot-connection-token"] = "test-token";
```

## Service tests

Test each service's Copilot code without a runtime, using its own integration's test support:

| Language | Test support |
| --- | --- |
| .NET | Fakes from the core .NET integration. See the [.NET docs](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/). |
| Spring Boot | The `copilot-spring-boot-test` module. See the [Spring Boot docs](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/). |
| Quarkus | The Quarkus test module. See the [Quarkus docs](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/). |
| Node.js | The scripted test runtime in the core and NestJS packages' `testing` entry points. See the [Node.js docs](https://brunoborges.github.io/copilot-sdk-integrations/nodejs/). |
| Python | No integration-specific support. Mock the SDK client in your tests. |

These tests don't involve Aspire. A service reads its connection from configuration, so a test sets
that configuration (or skips it and uses the fake).

## End-to-end tests

`Aspire.Hosting.Testing` starts the whole AppHost, including the Copilot runtime, from a test:

```cs
using Aspire.Hosting.Testing;

[Fact]
public async Task Web_answers()
{
    var appHost = await DistributedApplicationTestingBuilder.CreateAsync<Projects.AppHost>();
    await using var app = await appHost.BuildAsync();
    await app.StartAsync();

    await app.ResourceNotifications.WaitForResourceHealthyAsync("copilot");

    using var http = app.CreateHttpClient("web");
    var response = await http.GetAsync("/ask?q=hello");
    response.EnsureSuccessStatusCode();
}
```

End-to-end tests make real model calls. The runtime must be signed in (a developer sign-in locally,
or `WithGitHubToken` and a secret in CI), or every model must use a
[BYOK provider](models.md#bring-your-own-key-providers). Keep them few and run them separately from
unit tests.

## Testing this repository

The integration's own tests are in `aspire/tests`:

```bash
cd aspire
dotnet test --solution CopilotSdk.Aspire.slnx
```
