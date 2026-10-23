package workshop.micronaut;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import com.github.copilot.rpc.PermissionHandler;
import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.ToolPermissions;
import workshop.common.WikipediaResearch;
import com.github.copilot.rpc.McpStdioServerConfig;

@Factory
public class ToolsConfiguration {
    @Singleton
    BrowserOptions browserOptions(@Value("${workshop.browser.headless}") boolean headless,
            @Value("${workshop.browser.timeout-ms}") long timeoutMillis) {
        return new BrowserOptions(headless, timeoutMillis);
    }
    @Singleton @Named("museumTools")
    MuseumTools museumTools() { return new MuseumTools(); }
    @Singleton @Named("accessibilityTools")
    AccessibilityTools accessibilityTools() { return new AccessibilityTools(); }
    @Singleton @Named("museumPermissions")
    PermissionHandler museumPermissions(MuseumTools tools) { return ToolPermissions.museum(tools); }
    @Singleton @Named("accessibilityPermissions")
    PermissionHandler accessibilityPermissions(AccessibilityTools tools) { return ToolPermissions.accessibility(tools); }
    @Singleton @Named("wikipedia")
    McpStdioServerConfig wikipedia() { return WikipediaResearch.server(); }
    @Singleton @Named("researchPermissions")
    PermissionHandler researchPermissions() { return WikipediaResearch.permissions(); }
}
