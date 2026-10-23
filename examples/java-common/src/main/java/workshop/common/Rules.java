package workshop.common;

import java.util.Map;

public final class Rules {
    private Rules() {}
    public static final Map<String, String> CATALOG = Map.of(
            "1.1.1", "Non-text Content (A): provide text alternatives conveying the purpose of non-text content. A tree may not establish whether an image is decorative.",
            "1.3.1", "Info and Relationships (A): programmatically expose information, structure, and relationships. Verify visual relationships manually.",
            "2.4.4", "Link Purpose (In Context) (A): make each link's purpose determinable from its text and programmatic context.",
            "2.4.6", "Headings and Labels (AA): headings and labels describe topic or purpose.",
            "3.3.2", "Labels or Instructions (A): provide labels or instructions when content requires user input.",
            "4.1.2", "Name, Role, Value (A): expose name and role for UI components and applicable states, properties and values.");
}
