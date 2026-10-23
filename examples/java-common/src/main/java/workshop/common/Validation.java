package workshop.common;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

public final class Validation {
    private Validation() {}

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException("Rejected agent output: " + reason);
    }

    private static void fields(JsonNode node, String... expected) {
        require(node != null && node.isObject(), "expected object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(expected)), "unexpected or missing fields");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.asText().isBlank(), "missing text: " + field);
        return value.asText().strip();
    }

    public static JsonNode museum(String response, boolean consulted) {
        require(consulted, "approved_fact_lookup was not called");
        JsonNode result = Json.parse(response);
        fields(result, "title", "narrative", "questions");
        String title = text(result, "title");
        String narrative = text(result, "narrative");
        require(title.length() <= 200 && !title.contains("?") && !narrative.contains("?"), "title/narrative questions");
        int words = narrative.split("\\s+").length;
        require(words >= 100 && words <= 140, "narrative must contain 100-140 words");
        JsonNode questions = result.get("questions");
        require(questions.isArray() && questions.size() == 3, "exactly three questions required");
        Set<String> seen = new HashSet<>();
        for (JsonNode question : questions) {
            require(question.isTextual(), "question must be text");
            String q = question.asText().strip();
            require(q.length() > 1 && q.endsWith("?") && q.chars().filter(c -> c == '?').count() == 1,
                    "each question must end in exactly one question mark");
            require(seen.add(q.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")), "duplicate question");
        }
        return result;
    }

    public static JsonNode accessibility(String response, String snapshot, AccessibilityTools tools) {
        require(tools.snapshotRead(), "snapshot tool was not called");
        JsonNode result = Json.parse(response);
        fields(result, "findings", "reviewLimits");
        String limits = text(result, "reviewLimits").toLowerCase(Locale.ROOT);
        require(limits.contains("not") && limits.contains("conformance")
                && limits.contains("keyboard") && limits.contains("focus")
                && limits.contains("contrast") && limits.contains("dynamic"), "missing review limitations");
        JsonNode findings = result.get("findings");
        require(findings.isArray() && findings.size() >= 3 && findings.size() <= 5, "3-5 findings required");
        Set<String> seen = new HashSet<>();
        var remainingLookups = new HashMap<>(tools.lookups());
        for (JsonNode finding : findings) {
            fields(finding, "evidence", "criterion", "remediation");
            String evidence = text(finding, "evidence");
            String criterion = text(finding, "criterion");
            text(finding, "remediation");
            require(snapshot.contains(evidence), "evidence is not in this snapshot");
            require(Rules.CATALOG.containsKey(criterion) && remainingLookups.getOrDefault(criterion, 0) > 0,
                    "criterion must have been looked up locally");
            remainingLookups.merge(criterion, -1, Integer::sum);
            require(seen.add(criterion + "\n" + evidence), "duplicate finding");
        }
        return result;
    }
}
