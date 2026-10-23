package workshop.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;

/** A single invocation's file, never a caller-supplied path or a "latest" glob. */
public final class SnapshotStore implements AutoCloseable {
    public static final int MAX_BYTES = 256 * 1024;
    private final Path directory;
    private final Path snapshot;
    private final Instant started;
    private Object fileKey;
    private boolean written;

    public SnapshotStore(Path workspace) throws IOException {
        Path base = workspace.toRealPath();
        directory = Files.createDirectory(base.resolve(".workshop-run-" + UUID.randomUUID()));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        snapshot = directory.resolve("snapshot.yaml");
        // File timestamps may have coarse resolution; the unique directory and recorded file identity
        // are the primary freshness controls.
        started = Instant.now().minusSeconds(2);
    }

    public void write(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Empty or oversized snapshot");
        try (var channel = FileChannel.open(snapshot, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        fileKey = Files.readAttributes(snapshot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        written = true;
    }

    public String read() throws IOException {
        if (!written) throw new IOException("No snapshot captured for this invocation");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(snapshot)) throw new IOException("Symlink rejected");
        var attributes = Files.readAttributes(snapshot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() == 0 || attributes.size() > MAX_BYTES
                || attributes.lastModifiedTime().toInstant().isBefore(started)
                || (fileKey != null && !fileKey.equals(attributes.fileKey()))) {
            throw new IOException("Snapshot is stale, replaced, empty, or oversized");
        }
        try (var channel = FileChannel.open(snapshot, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            if (buffer.position() > MAX_BYTES) throw new IOException("Snapshot grew beyond limit");
            return StandardCharsets.UTF_8.decode(buffer.flip()).toString();
        }
    }

    @Override public void close() throws IOException {
        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(directory);
    }
}
