package workshop.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.JsonNode;

/** Only trusted templates become markup. Every model/page value is escaped text. */
public final class HtmlReport {
    private HtmlReport() {}

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String render(String mode, String target, JsonNode validated) throws IOException {
        StringBuilder body = new StringBuilder();
        if (mode.equals("museum")) {
            body.append("<h1>").append(escape(validated.get("title").asText())).append("</h1><p>")
                    .append(escape(validated.get("narrative").asText())).append("</p><h2>Explore together</h2><ol>");
            for (var question : validated.get("questions")) body.append("<li>").append(escape(question.asText())).append("</li>");
            body.append("</ol><h2>Educator review</h2><p>Structural checks do not prove factual grounding. ")
                    .append("An educator must verify all statements and questions against approved facts.</p>");
        } else if (mode.equals("accessibility")) {
            body.append("<h1>Accessibility snapshot review</h1><p>Target: <span>")
                    .append(escape(target)).append("</span></p><h2>Human-review limits</h2>")
                    .append("<p>This limited snapshot review is not WCAG conformance certification. Verify every finding manually.</p><p>")
                    .append(escape(validated.get("reviewLimits").asText()))
                    .append("</p><label for=\"filter\">Filter findings by text or criterion</label>")
                    .append("<input id=\"filter\" type=\"search\" aria-controls=\"findings\" autocomplete=\"off\">")
                    .append("<p id=\"count\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\">")
                    .append(validated.get("findings").size()).append(" findings shown</p><div id=\"findings\">");
            for (var finding : validated.get("findings")) {
                body.append("<section class=\"finding\"><h2>WCAG ").append(escape(finding.get("criterion").asText()))
                        .append("</h2><h3>Snapshot evidence</h3><pre>")
                        .append(escape(finding.get("evidence").asText())).append("</pre><h3>Suggested remediation</h3><p>")
                        .append(escape(finding.get("remediation").asText())).append("</p></section>");
            }
            body.append("</div><script>")
                    .append("const filter=document.getElementById('filter');")
                    .append("filter.addEventListener('input',()=>{let count=0;")
                    .append("for(const item of document.querySelectorAll('.finding')){")
                    .append("item.hidden=!item.textContent.toLocaleLowerCase().includes(filter.value.toLocaleLowerCase());")
                    .append("if(!item.hidden)count++;}")
                    .append("document.getElementById('count').textContent=count+' findings shown';});")
                    .append("</script>");
        } else {
            throw new IllegalArgumentException("No HTML renderer for this mode");
        }
        String css;
        try (var stream = HtmlReport.class.getResourceAsStream("/workshop/report.css")) {
            if (stream == null) throw new IOException("Missing report stylesheet");
            css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Workshop review</title>
                <script>
                  (() => {
                    const param = new URLSearchParams(window.location.search).get("clawpilotTheme");
                    const theme =
                      param || (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
                    document.documentElement.setAttribute("data-theme", theme);
                  })();
                </script>
                <style>
                """ + css + "</style></head><body><main>" + body + "</main></body></html>";
    }

    public static void write(String file, String html) throws IOException {
        if (!file.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.html")) {
            throw new IllegalArgumentException("HTML output must be a new .html filename in the current directory");
        }
        Files.writeString(Path.of(file), html, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
}
