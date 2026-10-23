package com.microsoft.copilot.integrations.quarkus.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.Config;

import com.microsoft.copilot.integrations.core.internal.PropertyResolver;

/** Resolves {@code ${key}} and {@code ${key:default}} placeholders against MicroProfile Config. */
public final class ConfigPropertyResolver implements PropertyResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private final Config config;

    public ConfigPropertyResolver(Config config) {
        this.config = config;
    }

    @Override
    public String resolveRequiredPlaceholders(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String resolved = config.getOptionalValue(key, String.class).orElse(matcher.group(2));
            if (resolved == null) {
                throw new IllegalArgumentException("Could not resolve placeholder '" + key + "' in value \"" + value + "\"");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
