package papyrus;

import java.io.IOException;
import java.nio.file.*;

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
        return ImageStorage.importImage(source, data);
    }
}
