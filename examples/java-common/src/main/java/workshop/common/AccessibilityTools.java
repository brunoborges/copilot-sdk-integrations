package workshop.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

public class AccessibilityTools {
    private SnapshotStore snapshot;
    private boolean read;
    private final Map<String, Integer> lookups = new HashMap<>();

    public void prepare(SnapshotStore snapshot) {
        this.snapshot = snapshot;
        read = false;
        lookups.clear();
    }

    @CopilotTool(name = "read_latest_accessibility_snapshot", value = "Read only this invocation's captured accessibility tree")
    public String readSnapshot() {
        if (snapshot == null) throw new IllegalStateException("No snapshot captured");
        try {
            String result = snapshot.read();
            read = true;
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read this invocation's snapshot", e);
        }
    }

    @CopilotTool(name = "accessibility_rule_lookup", value = "Read the local WCAG 2.2 catalog entry for one finding")
    public String rule(@CopilotToolParam("WCAG criterion ID, e.g. 4.1.2") String criterion) {
        String description = Rules.CATALOG.get(criterion);
        if (description == null) throw new IllegalArgumentException("Criterion is not in the local catalog");
        lookups.merge(criterion, 1, Integer::sum);
        return description;
    }

    public boolean snapshotRead() { return read; }
    public Map<String, Integer> lookups() { return Map.copyOf(lookups); }
}
