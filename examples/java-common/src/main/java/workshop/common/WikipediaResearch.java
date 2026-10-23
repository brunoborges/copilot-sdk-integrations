package workshop.common;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.copilot.rpc.McpStdioServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.integrations.core.CopilotPermissionPolicies;

public final class WikipediaResearch {
    private WikipediaResearch() {}

    public static McpStdioServerConfig server() {
        return new McpStdioServerConfig().setCommand("npx")
                .setArgs(List.of("-y", "wikipedia-mcp@1.0.3")).setTools(List.of("search", "readArticle"));
    }

    public static PermissionHandler permissions() {
        var names = CopilotPermissionPolicies.allowTools("search", "readArticle", "wikipedia-search", "wikipedia-readArticle");
        return (request, invocation) -> {
            if (!"mcp".equals(request.getKind()) || request.getExtensionData() == null
                    || !"wikipedia".equals(request.getExtensionData().get("serverName"))) {
                return CompletableFuture.completedFuture(PermissionRequestResult.reject("Only the wikipedia MCP server is permitted"));
            }
            return names.handle(request, invocation);
        };
    }

    public static JsonNode validate(String output) {
        JsonNode result = Json.parse(output);
        if (result == null || !result.isObject() || result.size() != 2 || !result.has("title") || !result.has("facts")
                || !result.path("title").isTextual() || result.path("title").asText().isBlank()
                || result.path("title").asText().length() > 200
                || !result.path("facts").isArray() || result.path("facts").isEmpty() || result.path("facts").size() > 20) {
            throw new IllegalArgumentException("Research requires title and 1-20 cited candidate facts");
        }
        for (var fact : result.get("facts")) {
            Set<String> fields = new java.util.HashSet<>();
            fact.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("text", "source")) || !fact.path("text").isTextual()
                    || !fact.path("source").isTextual() || fact.path("text").asText().isBlank()
                    || fact.path("text").asText().codePointCount(0, fact.path("text").asText().length()) > 500) {
                throw new IllegalArgumentException("Each research fact requires bounded text and source");
            }
            URI source = URI.create(fact.path("source").asText());
            if (!"https".equals(source.getScheme()) || !"en.wikipedia.org".equals(source.getHost())
                    || source.getPort() != -1 || source.getUserInfo() != null
                    || source.getQuery() != null || !source.getPath().startsWith("/wiki/")
                    || source.getPath().length() <= "/wiki/".length()) {
                throw new IllegalArgumentException("Research citation must be an English Wikipedia article URL");
            }
        }
        return result;
    }
}
