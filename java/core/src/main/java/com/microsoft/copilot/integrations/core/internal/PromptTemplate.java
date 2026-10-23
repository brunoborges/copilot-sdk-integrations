package com.microsoft.copilot.integrations.core.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


/** Renders the prompt of an agent method from its arguments. */
final class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private final int verbatim;
    private final List<Segment> segments;

    private PromptTemplate(int verbatim, List<Segment> segments) {
        this.verbatim = verbatim;
        this.segments = segments;
    }

    /** The argument at {@code index} is the prompt itself; validation is left to the invoker. */
    static PromptTemplate verbatim(int index) {
        return new PromptTemplate(index, List.of());
    }

    /**
     * Compiles {@code template}, requiring every placeholder to name a parameter and every parameter to be used.
     */
    static PromptTemplate compile(String template, Map<String, Integer> parameters, String method) {
        var segments = new ArrayList<Segment>();
        var unused = new LinkedHashSet<>(parameters.keySet());
        var matcher = PLACEHOLDER.matcher(template);
        int literal = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            Integer index = parameters.get(name);
            Assert.isTrue(index != null, "Prompt template of " + method + " references {{" + name
                    + "}} but no @P(\"" + name + "\") parameter is declared");
            unused.remove(name);
            if (matcher.start() > literal) {
                segments.add(new Segment(template.substring(literal, matcher.start()), -1, null));
            }
            segments.add(new Segment(null, index, name));
            literal = matcher.end();
        }
        if (literal < template.length()) {
            segments.add(new Segment(template.substring(literal), -1, null));
        }
        Assert.isTrue(unused.isEmpty(), "Prompt template of " + method + " does not reference @P parameter(s) " + unused);
        return new PromptTemplate(-1, List.copyOf(segments));
    }

    /**
     * Returns the prompt for {@code args}.
     *
     * @throws IllegalArgumentException if a template argument is null
     */
    String render(Object[] args) {
        if (verbatim != -1) {
            return (String) args[verbatim];
        }
        var prompt = new StringBuilder();
        for (var segment : segments) {
            if (segment.index() == -1) {
                prompt.append(segment.text());
            } else {
                Object value = args[segment.index()];
                if (value == null) {
                    throw new IllegalArgumentException("Prompt template argument '" + segment.name() + "' must not be null");
                }
                prompt.append(value);
            }
        }
        return prompt.toString();
    }

    private record Segment(String text, int index, String name) {
    }
}
