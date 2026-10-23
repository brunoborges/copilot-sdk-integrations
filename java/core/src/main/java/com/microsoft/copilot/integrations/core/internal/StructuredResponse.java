package com.microsoft.copilot.integrations.core.internal;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Maps a non-{@code String} agent result type onto a provider-native JSON Schema and reads the structured
 * assistant reply back into that type. Schemas follow strict structured-output rules: every object property is
 * required and closed, and non-object results are wrapped in a {@code {"value": ...}} object.
 */
public final class StructuredResponse {

    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private static final Set<Class<?>> STRINGS = Set.of(String.class, Character.class, char.class, UUID.class,
            Instant.class, LocalDate.class, LocalDateTime.class, LocalTime.class, OffsetDateTime.class,
            ZonedDateTime.class, Duration.class);
    private static final Set<Class<?>> INTEGERS = Set.of(byte.class, Byte.class, short.class, Short.class,
            int.class, Integer.class, long.class, Long.class, BigInteger.class);
    private static final Set<Class<?>> NUMBERS = Set.of(float.class, Float.class, double.class, Double.class,
            BigDecimal.class);

    private final JavaType type;
    private final Map<String, Object> schema;
    private final boolean wrapped;

    private StructuredResponse(JavaType type, Map<String, Object> schema, boolean wrapped) {
        this.type = type;
        this.schema = schema;
        this.wrapped = wrapped;
    }

    /**
     * Validates that {@code type} can be expressed as a strict response schema.
     *
     * @throws IllegalArgumentException if the type is unsupported
     */
    public static StructuredResponse of(Type type) {
        var javaType = MAPPER.constructType(type);
        var schema = schema(javaType, new HashSet<>());
        boolean wrapped = !"object".equals(schema.get("type"));
        if (wrapped) {
            schema = object(Map.of("value", schema));
        }
        return new StructuredResponse(javaType, schema, wrapped);
    }

    public Map<String, Object> schema() {
        return schema;
    }

    /** Converts the text future into a typed one; cancelling the result cancels the underlying invocation. */
    public CompletableFuture<Object> convert(CompletableFuture<String> response) {
        var converted = response.thenApply(this::read);
        converted.whenComplete((value, error) -> {
            if (converted.isCancelled()) {
                response.cancel(true);
            }
        });
        return converted;
    }

    public Object read(String content) {
        try {
            JsonNode tree = MAPPER.readTree(content);
            if (wrapped && tree != null && tree.isObject() && tree.has("value")) {
                tree = tree.get("value");
            }
            Object value = tree == null || tree.isNull() || tree.isMissingNode() ? null
                    : MAPPER.readerFor(type).readValue(tree);
            if (value == null) {
                throw new IllegalStateException("Copilot agent structured response was null, expected " + type.toCanonical());
            }
            return value;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Copilot agent response is not valid JSON for " + type.toCanonical(), ex);
        }
    }

    private static Map<String, Object> schema(JavaType type, Set<Class<?>> visiting) {
        Class<?> raw = type.getRawClass();
        if (STRINGS.contains(raw)) {
            return Map.of("type", "string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (INTEGERS.contains(raw)) {
            return Map.of("type", "integer");
        }
        if (NUMBERS.contains(raw)) {
            return Map.of("type", "number");
        }
        if (type.isEnumType()) {
            var values = new ArrayList<String>();
            for (Object constant : raw.getEnumConstants()) {
                values.add(MAPPER.convertValue(constant, String.class));
            }
            return Map.of("type", "string", "enum", List.copyOf(values));
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
            if (type.isArrayType() && type.getContentType().getRawClass() == byte.class) {
                throw unsupported(type, "binary arrays are not supported");
            }
            return Map.of("type", "array", "items", schema(type.getContentType(), visiting));
        }
        if (type.isMapLikeType()) {
            throw unsupported(type, "maps cannot be expressed as strict schemas; use a record");
        }
        if (type.isReferenceType() || raw.isPrimitive() || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())
                || raw.getName().startsWith("java.") || raw.getName().startsWith("javax.")) {
            throw unsupported(type, "use String, a boxed primitive, an enum, a collection, or a record/JavaBean");
        }
        if (!visiting.add(raw)) {
            throw unsupported(type, "recursive types are not supported");
        }
        var properties = new LinkedHashMap<String, Object>();
        for (var property : MAPPER.getDeserializationConfig().introspect(type).findProperties()) {
            if (property.couldDeserialize()) {
                properties.put(property.getName(), schema(property.getPrimaryType(), visiting));
            }
        }
        visiting.remove(raw);
        if (properties.isEmpty()) {
            throw unsupported(type, "no deserializable properties were found");
        }
        return object(properties);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)),
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static IllegalArgumentException unsupported(JavaType type, String reason) {
        return new IllegalArgumentException("Unsupported structured result type " + type.toCanonical() + ": " + reason);
    }
}
