package workshop.quarkus;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.github.copilot.rpc.PermissionHandler;
import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.ToolPermissions;
import workshop.common.WikipediaResearch;
import com.github.copilot.rpc.McpStdioServerConfig;

@Singleton
public class ToolsConfiguration {
    @Produces @Singleton
    BrowserOptions browserOptions(@ConfigProperty(name = "workshop.browser.headless") boolean headless,
            @ConfigProperty(name = "workshop.browser.timeout-ms") long timeoutMillis) {
        return new BrowserOptions(headless, timeoutMillis);
    }
    @Produces @Singleton @Named("museumTools")
    MuseumTools museumTools() { return new MuseumTools(); }
    @Produces @Singleton @Named("accessibilityTools")
    AccessibilityTools accessibilityTools() { return new AccessibilityTools(); }
    @Produces @Singleton @Named("museumPermissions")
    PermissionHandler museumPermissions(MuseumTools tools) { return ToolPermissions.museum(tools); }
    @Produces @Singleton @Named("accessibilityPermissions")
    PermissionHandler accessibilityPermissions(AccessibilityTools tools) { return ToolPermissions.accessibility(tools); }
    @Produces @Singleton @Named("wikipedia")
    McpStdioServerConfig wikipedia() { return WikipediaResearch.server(); }
    @Produces @Singleton @Named("researchPermissions")
    PermissionHandler researchPermissions() { return WikipediaResearch.permissions(); }
}
