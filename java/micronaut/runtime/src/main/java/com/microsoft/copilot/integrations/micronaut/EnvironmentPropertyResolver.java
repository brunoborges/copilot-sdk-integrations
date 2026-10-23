package com.microsoft.copilot.integrations.micronaut;

import com.microsoft.copilot.integrations.core.internal.PropertyResolver;
import io.micronaut.context.env.Environment;

/** Resolves {@code ${key}} and {@code ${key:default}} placeholders against the Micronaut environment. */
final class EnvironmentPropertyResolver implements PropertyResolver {

    private final Environment environment;

    EnvironmentPropertyResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolveRequiredPlaceholders(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        return environment.getPlaceholderResolver().resolveRequiredPlaceholders(value);
    }
}
