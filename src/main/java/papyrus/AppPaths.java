package papyrus;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

final class AppPaths {
    static Path dataDirectory() {
        String override = System.getProperty("papyrus.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        if (Files.isRegularFile(Path.of("database", "test1.db"))) return Path.of("database").toAbsolutePath();
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) return Path.of(System.getenv().getOrDefault("APPDATA", home), "Papyrus");
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "Papyrus");
        return Path.of(System.getenv().getOrDefault("XDG_DATA_HOME", Path.of(home, ".local", "share").toString()), "papyrus");
    }

    static String importImage(Path source, Path data) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of(".png", ".jpg", ".jpeg").contains(extension)) throw new IOException("Unsupported image");
        if (Files.size(source) > 10 * 1024 * 1024) throw new IOException("Image exceeds 10 MB");
        Path destination = Files.createDirectories(data.resolve("images")).resolve(UUID.randomUUID() + extension);
        Files.copy(source, destination);
        return destination.toUri().toString();
    }
}
