package com.microsoft.copilot.integrations.core.internal;

import java.util.Collection;
import java.util.function.Supplier;

/** Argument checks raising {@link IllegalArgumentException}, mirroring the subset of Spring's {@code Assert} used here. */
public final class Assert {

    private Assert() {
    }

    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void isTrue(boolean expression, Supplier<String> message) {
        if (!expression) {
            throw new IllegalArgumentException(message.get());
        }
    }

    public static void state(boolean expression, String message) {
        if (!expression) {
            throw new IllegalStateException(message);
        }
    }

    public static void notNull(Object value, String message) {
        isTrue(value != null, message);
    }

    public static void hasText(String value, String message) {
        isTrue(value != null && !value.isBlank(), message);
    }

    public static void hasText(String value, Supplier<String> message) {
        isTrue(value != null && !value.isBlank(), message);
    }

    public static void notEmpty(Collection<?> values, String message) {
        isTrue(values != null && !values.isEmpty(), message);
    }

    public static void noNullElements(Collection<?> values, String message) {
        if (values != null) {
            for (Object value : values) {
                isTrue(value != null, message);
            }
        }
    }

    public static void isInstanceOf(Class<?> type, Object value, String message) {
        isTrue(type.isInstance(value), message);
    }
}
