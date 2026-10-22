package io.github.brunoborges.copilot.agent.internal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens UTF-8 resources referenced by {@code instructionsResource} and {@code @UserMessage(resource = ...)}.
 * Locations are class-path resources, optionally prefixed with {@code classpath:}, or {@code file:} paths.
 */
@FunctionalInterface
public interface ResourceLookup {

    InputStream open(String location) throws IOException;

    static ResourceLookup classpath(ClassLoader loader) {
        return location -> {
            if (location.startsWith("file:")) {
                return Files.newInputStream(Path.of(location.substring("file:".length())));
            }
            String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            ClassLoader effective = loader != null ? loader : Thread.currentThread().getContextClassLoader();
            InputStream input = effective == null ? null : effective.getResourceAsStream(path);
            if (input == null) {
                throw new FileNotFoundException("Class-path resource not found: " + location);
            }
            return input;
        };
    }
}
