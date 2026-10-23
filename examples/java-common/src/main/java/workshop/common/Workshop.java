package workshop.common;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class Workshop {
    private Workshop() {}

    @FunctionalInterface
    public interface Agent {
        CompletableFuture<String> run(String conversation, String task);
    }

    public static void run(String[] args, MuseumTools museum, AccessibilityTools accessibility,
            Agent curator, Agent reviewer, Agent researcher, BrowserOptions browser) throws Exception {
        if ((args.length != 2 && args.length != 3)
                || !(args[0].equals("museum") || args[0].equals("accessibility") || args[0].equals("research"))) {
            throw new IllegalArgumentException("Usage: museum <apollo11|reef|terracotta|approved.json> [new.html] | accessibility <exact-http(s)-URL> [new.html] | research <topic>");
        }
        String id = UUID.randomUUID().toString();
        if (args[0].equals("research")) {
            if (args.length != 2 || args[1].isBlank() || args[1].length() > 500) {
                throw new IllegalArgumentException("research requires a topic of 1-500 characters, and no HTML argument");
            }
            var output = researcher.run(id, Json.encode(Map.of("topic", args[1]))).get();
            System.out.println(Json.encode(WikipediaResearch.validate(output)));
            System.err.println("Unapproved research candidates. Citation shape is checked, not source fidelity. Educator verification required.");
        } else if (args[0].equals("museum")) {
            Exhibit exhibit = Exhibit.selected(args[1]);
            museum.prepare(exhibit);
            String output = curator.run(id, Json.encode(Map.of("exhibitTitle", exhibit.title()))).get();
            var validated = Validation.museum(output, museum.consulted());
            if (args.length == 3) HtmlReport.write(args[2], HtmlReport.render("museum", exhibit.title(), validated));
            System.out.println(Json.encode(validated));
            System.err.println("Structural checks passed; they do NOT prove factual grounding. Educator review required.");
        } else {
            String target = BrowserCapture.validateTarget(args[1]);
            try (var snapshot = new SnapshotStore(Path.of("."))) {
                BrowserCapture.capture(target, snapshot, browser);
                accessibility.prepare(snapshot);
                String output = reviewer.run(id, Json.encode(Map.of("targetUrl", target,
                        "task", "Review the captured accessibility snapshot"))).get();
                var validated = Validation.accessibility(output, snapshot.read(), accessibility);
                if (args.length == 3) HtmlReport.write(args[2], HtmlReport.render("accessibility", target, validated));
                System.out.println(Json.encode(validated));
                System.err.println("Limited evidence review, not conformance certification. Human verification required.");
            }
        }
    }
}
