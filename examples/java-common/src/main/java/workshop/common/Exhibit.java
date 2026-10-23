package workshop.common;

import java.util.List;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

public record Exhibit(String title, List<String> facts) {
    public Exhibit {
        if (title == null || title.isBlank() || title.length() > 200
                || facts == null || facts.isEmpty() || facts.size() > 20) {
            throw new IllegalArgumentException("Exhibit needs a title and 1-20 educator-approved facts");
        }
        facts = List.copyOf(facts);
        if (facts.stream().anyMatch(f -> f.isBlank() || f.codePointCount(0, f.length()) > 500)) {
            throw new IllegalArgumentException("Each approved fact must be nonempty and <=500 characters");
        }
    }

    public static Exhibit bundled(String choice) {
        return switch (choice) {
            case "apollo11" -> new Exhibit("Apollo 11", List.of(
                    "Apollo 11 landed humans on the Moon in July 1969.",
                    "Neil Armstrong and Buzz Aldrin walked on the lunar surface.",
                    "Michael Collins remained in lunar orbit aboard the command module.",
                    "The astronauts collected lunar samples and returned them to Earth."));
            case "reef" -> new Exhibit("Coral reef", List.of(
                    "Corals are animals that live in colonies.",
                    "Many reef-building corals share a relationship with algae living in their tissues.",
                    "Coral reefs provide habitat for many marine species.",
                    "Ocean warming can stress corals and contribute to bleaching."));
            case "terracotta" -> new Exhibit("Terracotta army", List.of(
                    "The terracotta army was created for the tomb complex of Qin Shi Huang in China.",
                    "The figures include soldiers, horses, and chariots.",
                    "The life-sized figures have varied facial features and clothing details.",
                    "Farmers discovered figures near Xi'an in 1974."));
            default -> throw new IllegalArgumentException("Choose apollo11, reef, or terracotta");
        };
    }

    public static Exhibit selected(String choice) throws IOException {
        if (List.of("apollo11", "reef", "terracotta").contains(choice)) return bundled(choice);
        if (!choice.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.json")) {
            throw new IllegalArgumentException("Choose a bundled exhibit or a local approved .json filename");
        }
        try (var channel = FileChannel.open(Path.of(choice), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(64 * 1024 + 1);
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            if (bytes.position() > 64 * 1024) throw new IOException("Approved facts file too large");
            var data = Json.parse(StandardCharsets.UTF_8.decode(bytes.flip()).toString());
            if (!data.isObject() || data.size() != 3 || !data.path("educatorApproved").isBoolean()
                    || !data.path("educatorApproved").asBoolean() || !data.path("title").isTextual()
                    || !data.path("facts").isArray()) {
                throw new IllegalArgumentException("Expected title, educatorApproved:true, and facts array");
            }
            var facts = new java.util.ArrayList<String>();
            for (var fact : data.get("facts")) {
                if (!fact.isTextual()) throw new IllegalArgumentException("Approved facts must be strings");
                facts.add(fact.asText());
            }
            return new Exhibit(data.get("title").asText(), facts);
        }
    }
}
