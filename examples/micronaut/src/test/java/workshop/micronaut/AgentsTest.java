package workshop.micronaut;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.context.annotation.Property;
import com.microsoft.copilot.integrations.micronaut.test.CopilotTestRuntime;
import workshop.common.*;

@MicronautTest(startApplication = false)
@Property(name = "micronaut.copilot.agents.museum.model", value = "offline-test-model")
@Property(name = "workshop.browser.headless", value = "false")
@Property(name = "workshop.browser.timeout-ms", value = "1234")
class AgentsTest {
    @Inject CopilotTestRuntime copilot;
    @Inject MuseumAgent museum;
    @Inject AccessibilityAgent accessibility;
    @Inject ResearchAgent research;
    @Inject MuseumTools museumTools;
    @Inject AccessibilityTools accessibilityTools;
    @Inject BrowserOptions browser;

    private static String prompt(String name) throws java.io.IOException {
        try (var stream = AgentsTest.class.getResourceAsStream("/workshop/prompts/" + name + ".txt")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void injectedAgentsUseRegisteredReadOnlyToolsAndDurablePolicies() throws Exception {
        museumTools.prepare(Exhibit.bundled("apollo11"));
        copilot.agent("museum").invokesTool("approved_fact_lookup", Map.of())
                .repliesUsing(turn -> String.valueOf(turn.toolResult("approved_fact_lookup")));
        assertTrue(museum.run("museum-test", Json.encode(Map.of("exhibitTitle", "Apollo 11"))).join().contains("1969"));
        assertTrue(museumTools.consulted());
        assertEquals(prompt("museum"), copilot.sessionConfig("museum").getSystemMessage().getContent());
        assertEquals("offline-test-model", copilot.sessionConfig("museum").getModel());
        assertFalse(browser.headless());
        assertEquals(1234, browser.timeoutMillis());
        assertEquals(List.of("custom:approved_fact_lookup"), copilot.sessionConfig("museum").getAvailableTools());
        try (var snapshot = new SnapshotStore(Path.of("target"))) {
            snapshot.write("- button");
            accessibilityTools.prepare(snapshot);
            copilot.agent("accessibility").invokesTool("read_latest_accessibility_snapshot", Map.of())
                    .invokesTool("accessibility_rule_lookup", Map.of("criterion", "4.1.2")).replies("reviewed");
            assertEquals("reviewed", accessibility.run("review-test", "{}").join());
            assertTrue(accessibilityTools.snapshotRead());
            assertEquals(1, accessibilityTools.lookups().get("4.1.2"));
            assertEquals(prompt("accessibility"), copilot.sessionConfig("accessibility").getSystemMessage().getContent());
            assertEquals(2, copilot.sessionConfig("accessibility").getAvailableTools().size());
        }

    }

    @Test void researchIsSeparateAndHasOnlyTheScopedWikipediaServer() throws Exception {
        copilot.agent("research").replies("{\"title\":\"Candidates\",\"facts\":[]}");
        research.run("research-test", "{\"topic\":\"Apollo 11\"}").join();
        var config = copilot.sessionConfig("research");
        assertEquals(prompt("research"), config.getSystemMessage().getContent());
        assertEquals(List.of("search", "readArticle"), config.getMcpServers().get("wikipedia").getTools());
        assertEquals(1, config.getMcpServers().size());
        assertTrue(config.getAvailableTools().stream().allMatch(name -> name.startsWith("mcp:")));
    }
}
