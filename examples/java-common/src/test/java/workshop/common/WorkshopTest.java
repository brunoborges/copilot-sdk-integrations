package workshop.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.copilot.rpc.PermissionRequest;

class WorkshopTest {
    Path workspace;

    @BeforeEach void workspace() throws IOException {
        Files.createDirectories(Path.of("target"));
        workspace = Files.createDirectory(Path.of("target", "test-" + UUID.randomUUID()));
    }

    @AfterEach void cleanup() throws IOException {
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    static String museum() {
        return Json.encode(Map.of("title", "Exploration", "narrative", "word ".repeat(100).strip(),
                "questions", List.of("What do you notice?", "How might it feel?", "What would you ask?")));
    }

    @Test void bundledFactsAreBoundedAndSelectionIsClosed() {
        for (String choice : List.of("apollo11", "reef", "terracotta")) assertEquals(4, Exhibit.bundled(choice).facts().size());
        assertThrows(IllegalArgumentException.class, () -> Exhibit.bundled("outside"));
        assertThrows(IllegalArgumentException.class, () -> new Exhibit("x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Exhibit("x", Collections.nCopies(21, "fact")));
        assertThrows(IllegalArgumentException.class, () -> new Exhibit("x", List.of("x".repeat(501))));
        assertThrows(IllegalArgumentException.class, () -> new Exhibit("x", List.of(" ")));
        assertEquals(20, new Exhibit("x", Collections.nCopies(20, "x".repeat(500))).facts().size());
    }

    @Test void approvedFactsToolIsScopedAndReadOnly() {
        var tools = new MuseumTools();
        assertThrows(IllegalStateException.class, tools::approvedFacts);
        tools.prepare(Exhibit.bundled("reef"));
        assertFalse(tools.consulted());
        assertTrue(tools.approvedFacts().contains("Corals are animals"));
        assertTrue(tools.consulted());
        tools.prepare(Exhibit.bundled("apollo11"));
        assertFalse(tools.consulted());
        assertFalse(tools.approvedFacts().contains("Corals"));
    }

    @Test void museumAcceptsStructureButDoesNotEstablishTruth() {
        assertEquals("Exploration", Validation.museum(museum(), true).get("title").asText());
        assertThrows(IllegalArgumentException.class, () -> Validation.museum(museum(), false));
        for (int words : List.of(99, 141)) {
            ObjectNode output = (ObjectNode) Json.parse(museum());
            output.put("narrative", "word ".repeat(words));
            assertThrows(IllegalArgumentException.class, () -> Validation.museum(output.toString(), true));
        }
        for (String questions : List.of("[\"One?\",\"One?\",\"Three?\"]", "[\"One?\",\"Two?\"]",
                "[\"One?\",\"Two?\",\"Three\"]", "[\"One??\",\"Two?\",\"Three?\"]")) {
            ObjectNode output = (ObjectNode) Json.parse(museum());
            output.set("questions", Json.parse(questions));
            assertThrows(IllegalArgumentException.class, () -> Validation.museum(output.toString(), true));
        }
    }

    @Test void malformedJsonMissingFieldsAndExtraFieldsFailClosed() {
        for (String bad : List.of("```json\n" + museum() + "\n```", museum() + " {}", "{}",
                "{\"title\":\"one\",\"title\":\"two\"}", museum().replace("\"title\":", "\"extra\":"))) {
            assertThrows(IllegalArgumentException.class, () -> Validation.museum(bad, true));
        }
    }

    @Test void permissionsOnlyApproveRegisteredCustomTools() {
        var permission = ToolPermissions.museum(new MuseumTools());
        assertEquals("approve-once", permission.handle(request("custom-tool", "approved_fact_lookup"), null).join().getKind());
        for (String kind : List.of("shell", "read", "write", "url", "mcp", "unknown")) {
            assertNotEquals("approve-once", permission.handle(request(kind, "approved_fact_lookup"), null).join().getKind());
        }
        assertNotEquals("approve-once", permission.handle(request("custom-tool", "browser_navigate"), null).join().getKind());
        var accessibility = ToolPermissions.accessibility(new AccessibilityTools());
        for (String name : List.of("accessibility_rule_lookup", "read_latest_accessibility_snapshot")) {
            assertEquals("approve-once", accessibility.handle(request("custom-tool", name), null).join().getKind());
        }
        assertNotEquals("approve-once", accessibility.handle(request("custom-tool", "approved_fact_lookup"), null).join().getKind());
    }

    static PermissionRequest request(String kind, String tool) {
        return Json.MAPPER.convertValue(Map.of("kind", kind, "toolName", tool), PermissionRequest.class);
    }

    @Test void exactTargetAndGetOnlyCapturePolicy() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserOptions(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new BrowserOptions(true, -1));
        String target = "https://example.com/";
        assertEquals(target, BrowserCapture.validateTarget(target));
        assertTrue(BrowserCapture.allowedRequest(target, target, "GET", true));
        assertTrue(BrowserCapture.allowedRequest(target, "https://cdn.example.com/image.png", "GET", false));
        assertFalse(BrowserCapture.allowedRequest(target, "https://example.com/other", "GET", true));
        assertFalse(BrowserCapture.allowedRequest(target, target, "POST", false));
        assertFalse(BrowserCapture.allowedRequest(target, "file:///etc/passwd", "GET", false));
        for (String invalid : List.of("file:///etc/passwd", "javascript:alert(1)", "https://u:p@example.com/",
                "/relative", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> BrowserCapture.validateTarget(invalid));
        }
    }

    Path snapshotPath() throws IOException {
        try (var paths = Files.list(workspace)) { return paths.findFirst().orElseThrow().resolve("snapshot.yaml"); }
    }

    @Test void snapshotIsPerRunBoundedAndDeleted() throws IOException {
        try (var store = new SnapshotStore(workspace)) {
            assertThrows(IOException.class, store::read);
            assertThrows(IOException.class, () -> store.write("x".repeat(SnapshotStore.MAX_BYTES + 1)));
            assertThrows(IOException.class, () -> store.write(""));
            store.write("- button");
            assertEquals("- button", store.read());
            assertThrows(IOException.class, () -> store.write("replacement"));
        }
        try (var files = Files.list(workspace)) { assertEquals(0, files.count()); }
    }

    @Test void staleOversizedAndSymlinkSnapshotsAreRejected() throws IOException {
        try (var store = new SnapshotStore(workspace)) {
            store.write("- button");
            Path snapshot = snapshotPath();
            Files.setLastModifiedTime(snapshot, FileTime.from(Instant.EPOCH));
            assertThrows(IOException.class, store::read);
            Files.writeString(snapshot, "x".repeat(SnapshotStore.MAX_BYTES + 1));
            assertThrows(IOException.class, store::read);
            Files.delete(snapshot);
            Path other = workspace.resolve("outside.yaml");
            Files.writeString(other, "untrusted");
            Files.createSymbolicLink(snapshot, other.toAbsolutePath());
            assertThrows(IOException.class, store::read);
            Files.delete(other);
        }
    }

    static String report() {
        return Json.encode(Map.of("findings", List.of(
                Map.of("evidence", "- button", "criterion", "4.1.2", "remediation", "Give the button an accessible name."),
                Map.of("evidence", "- link \"click here\"", "criterion", "2.4.4", "remediation", "Describe the link destination."),
                Map.of("evidence", "- textbox", "criterion", "3.3.2", "remediation", "Associate a descriptive label.")),
                "reviewLimits", "Limited snapshot review, not conformance certification. Keyboard, focus, contrast and dynamic behavior untested."));
    }

    @Test void accessibilityRequiresActualEvidenceAndEachLocalLookup() throws IOException {
        try (var store = new SnapshotStore(workspace)) {
            String tree = "- button\n- link \"click here\"\n- textbox";
            store.write(tree);
            var tools = new AccessibilityTools();
            tools.prepare(store);
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(report(), tree, tools));
            tools.readSnapshot();
            tools.rule("4.1.2");
            tools.rule("2.4.4");
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(report(), tree, tools));
            tools.rule("3.3.2");
            assertEquals(3, Validation.accessibility(report(), tree, tools).get("findings").size());
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(report(), "different snapshot", tools));
            assertThrows(IllegalArgumentException.class, () -> tools.rule("9.9.9"));
            ObjectNode result = (ObjectNode) Json.parse(report());
            result.put("reviewLimits", "Everything passes");
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(result.toString(), tree, tools));
        }
    }

    @Test void accessibilityRejectsMissingFindingsAndReusedLookups() throws IOException {
        try (var store = new SnapshotStore(workspace)) {
            store.write("first second third");
            var tools = new AccessibilityTools();
            tools.prepare(store);
            tools.readSnapshot();
            tools.rule("4.1.2");
            ObjectNode result = (ObjectNode) Json.parse(report());
            result.set("findings", Json.parse(Json.encode(List.of(
                    Map.of("evidence", "first", "criterion", "4.1.2", "remediation", "label"),
                    Map.of("evidence", "second", "criterion", "4.1.2", "remediation", "label"),
                    Map.of("evidence", "third", "criterion", "4.1.2", "remediation", "label")))));
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(result.toString(), store.read(), tools));
            tools.rule("4.1.2");
            tools.rule("4.1.2");
            assertEquals(3, Validation.accessibility(result.toString(), store.read(), tools).get("findings").size());
            result.set("findings", Json.parse("[]"));
            assertThrows(IllegalArgumentException.class, () -> Validation.accessibility(result.toString(), store.read(), tools));
        }
    }

    @Test void researchPermissionsRequireBothExactServerAndTool() {
        var policy = WikipediaResearch.permissions();
        for (String name : List.of("search", "readArticle", "wikipedia-search", "wikipedia-readArticle")) {
            var request = Json.MAPPER.convertValue(Map.of("kind", "mcp", "serverName", "wikipedia", "toolName", name), PermissionRequest.class);
            assertEquals("approve-once", policy.handle(request, null).join().getKind());
        }
        for (Map<String, String> denied : List.of(
                Map.of("kind", "mcp", "serverName", "other", "toolName", "search"),
                Map.of("kind", "custom-tool", "serverName", "wikipedia", "toolName", "search"),
                Map.of("kind", "mcp", "serverName", "wikipedia", "toolName", "delete"),
                Map.of("kind", "mcp", "toolName", "search"))) {
            assertNotEquals("approve-once", policy.handle(Json.MAPPER.convertValue(denied, PermissionRequest.class), null).join().getKind());
        }
        assertEquals(List.of("-y", "wikipedia-mcp@1.0.3"), WikipediaResearch.server().getArgs());
    }

    @Test void researchCandidatesRequireWikipediaCitations() {
        var candidate = Map.of("title", "Apollo", "facts", List.of(Map.of("text", "Candidate fact", "source",
                "https://en.wikipedia.org/wiki/Apollo_11")));
        assertEquals(1, WikipediaResearch.validate(Json.encode(candidate)).get("facts").size());
        for (String source : List.of("https://evil.example/wiki/Apollo_11", "javascript:alert(1)",
                "https://en.wikipedia.org.evil.example/wiki/Apollo_11", "https://en.wikipedia.org/wiki/",
                "https://user@en.wikipedia.org/wiki/Apollo_11")) {
            assertThrows(IllegalArgumentException.class, () -> WikipediaResearch.validate(Json.encode(
                    Map.of("title", "Apollo", "facts", List.of(Map.of("text", "Candidate", "source", source))))));
        }
    }

    @Test void htmlEscapesUntrustedContentAndIncludesAccessibleFilter() throws IOException {
        ObjectNode report = (ObjectNode) Json.parse(report());
        ((ObjectNode) report.get("findings").get(0)).put("evidence", "</pre><script>alert('x')</script>");
        String html = HtmlReport.render("accessibility", "<img src=x onerror=alert(1)>", report);
        assertFalse(html.contains("<script>alert"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("aria-live=\"polite\""));
        assertTrue(html.contains("label for=\"filter\""));
        assertTrue(html.contains("textContent=count"));
        assertTrue(html.contains("--cp-bg:"));
        String museumHtml = HtmlReport.render("museum", "Apollo", Validation.museum(museum(), true));
        assertTrue(museumHtml.contains("Educator review"));
        assertThrows(IllegalArgumentException.class, () -> HtmlReport.write("../escape.html", html));
    }

    @Test void htmlWriterNeverOverwritesExistingFilesOrSymlinks() throws IOException {
        String file = "workshop-test-" + UUID.randomUUID() + ".html";
        Path path = Path.of(file);
        try {
            HtmlReport.write(file, "<p>trusted</p>");
            assertThrows(IOException.class, () -> HtmlReport.write(file, "<p>replace</p>"));
            Files.delete(path);
            Files.createSymbolicLink(path, workspace.toAbsolutePath().resolve("not-created"));
            assertThrows(IOException.class, () -> HtmlReport.write(file, "<p>replace</p>"));
            assertFalse(Files.exists(workspace.resolve("not-created")));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test void approvedFilesRequireExplicitAcknowledgmentAndRejectSymlinks() throws IOException {
        String filename = "approved-test-" + UUID.randomUUID() + ".json";
        Path file = Path.of(filename);
        try {
            Files.writeString(file, Json.encode(Map.of("title", "Checked", "educatorApproved", false, "facts", List.of("Checked fact"))));
            assertThrows(IllegalArgumentException.class, () -> Exhibit.selected(filename));
            Files.writeString(file, Json.encode(Map.of("title", "Checked", "educatorApproved", true, "facts", List.of("Checked fact"))));
            assertEquals("Checked", Exhibit.selected(filename).title());
            Files.delete(file);
            Files.createSymbolicLink(file, workspace.toAbsolutePath().resolve("outside"));
            assertThrows(IOException.class, () -> Exhibit.selected(filename));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
