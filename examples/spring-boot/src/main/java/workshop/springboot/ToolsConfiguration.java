package workshop.springboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.github.copilot.rpc.PermissionHandler;
import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.ToolPermissions;
import workshop.common.WikipediaResearch;
import com.github.copilot.rpc.McpStdioServerConfig;

@Configuration
public class ToolsConfiguration {
    @Bean BrowserOptions browserOptions(@Value("${workshop.browser.headless}") boolean headless,
            @Value("${workshop.browser.timeout-ms}") long timeoutMillis) {
        return new BrowserOptions(headless, timeoutMillis);
    }
    @Bean MuseumTools museumTools() { return new MuseumTools(); }
    @Bean AccessibilityTools accessibilityTools() { return new AccessibilityTools(); }
    @Bean PermissionHandler museumPermissions(MuseumTools tools) { return ToolPermissions.museum(tools); }
    @Bean PermissionHandler accessibilityPermissions(AccessibilityTools tools) { return ToolPermissions.accessibility(tools); }
    @Bean McpStdioServerConfig wikipedia() { return WikipediaResearch.server(); }
    @Bean PermissionHandler researchPermissions() { return WikipediaResearch.permissions(); }
}
