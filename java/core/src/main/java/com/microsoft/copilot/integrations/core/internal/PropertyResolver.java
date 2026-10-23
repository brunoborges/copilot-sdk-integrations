package com.microsoft.copilot.integrations.core.internal;

/** Resolves {@code ${key}} and {@code ${key:default}} placeholders in annotation values. */
@FunctionalInterface
public interface PropertyResolver {

    PropertyResolver NONE = value -> value;

    /**
     * @throws IllegalArgumentException if a placeholder without default cannot be resolved
     */
    String resolveRequiredPlaceholders(String value);
}
