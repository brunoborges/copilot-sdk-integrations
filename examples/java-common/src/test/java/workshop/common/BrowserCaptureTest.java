package workshop.common;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.sun.net.httpserver.HttpServer;
import com.microsoft.playwright.Playwright;

@EnabledIfSystemProperty(named = "workshop.browserTest", matches = "true")
class BrowserCaptureTest {
    @Test void capturesARealLocalPageAndRejectsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pageVisits = new AtomicInteger();
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/")) pageVisits.incrementAndGet();
            byte[] html = "<html lang=\"en\"><title>Fixture</title><body><button>Save</button><input><a href=\"/\">click here</a></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.length);
            try (var body = exchange.getResponseBody()) { body.write(html); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            try (var store = new SnapshotStore(Path.of("target"))) {
                BrowserCapture.capture(target, store, new BrowserOptions(true, 30_000));
                assertTrue(store.read().contains("button \"Save\""));
            }
            try (var store = new SnapshotStore(Path.of("target"))) {
                assertThrows(Exception.class, () -> BrowserCapture.capture(target + "redirect", store, new BrowserOptions(true, 30_000)));
                assertThrows(java.io.IOException.class, store::read);
            }
            assertEquals(1, pageVisits.get(), "Redirect destination must never be visited");
        } finally {
            server.stop(0);
        }
    }

    @Test void standaloneReportFilterWorksWithoutInjectingPageContent() throws Exception {
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse(WorkshopTest.report());
        ((com.fasterxml.jackson.databind.node.ObjectNode) report.get("findings").get(0))
                .put("evidence", "</pre><script>window.compromised=true</script>");
        String html = HtmlReport.render("accessibility", "https://example.com/", report);
        try (var playwright = Playwright.create(); var browser = playwright.chromium().launch()) {
            var page = browser.newPage();
            page.setContent(html);
            assertEquals(3, page.locator(".finding:visible").count());
            assertEquals(false, page.evaluate("Boolean(window.compromised)"));
            page.getByLabel("Filter findings by text or criterion").fill("2.4.4");
            assertEquals(1, page.locator(".finding:visible").count());
            assertEquals("1 findings shown", page.locator("#count").textContent());
            page.locator("#filter").fill("no-matching-finding");
            assertEquals(0, page.locator(".finding:visible").count());
            assertEquals("0 findings shown", page.locator("#count").textContent());
            for (int width : java.util.List.of(375, 1280)) {
                page.setViewportSize(width, 800);
                assertEquals(true, page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
            }
        }
    }
}
