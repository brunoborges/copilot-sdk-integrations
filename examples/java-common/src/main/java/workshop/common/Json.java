package workshop.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    private Json() {}
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize task", e);
        }
    }

    public static JsonNode parse(String value) {
        if (value == null || value.length() > 100_000) {
            throw new IllegalArgumentException("Missing or oversized JSON");
        }
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Expected one strict JSON value", e);
        }
    }
}
