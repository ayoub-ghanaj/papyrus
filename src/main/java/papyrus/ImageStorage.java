package papyrus;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Managed images have relative names so a complete library can move between computers. */
final class ImageStorage {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 24_000_000L;

    static String importImage(Path source, Path directory) throws IOException {
        validate(source);
        String extension = extension(source);
        Path target = Files.createDirectories(directory.resolve("images")).resolve(UUID.randomUUID() + extension);
        try { Files.copy(source, target); validate(target); return "images/" + target.getFileName(); }
        catch (IOException | RuntimeException ex) { Files.deleteIfExists(target); throw ex; }
    }

    static void validate(Path source) throws IOException {
        if (!Set.of(".png", ".jpg", ".jpeg").contains(extension(source)) || Files.size(source) > MAX_BYTES) throw new IOException("Invalid image size or format");
        try (var stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) throw new IOException("Invalid image");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!Set.of("png", "jpeg", "jpg").contains(format) || (long) width * height > MAX_PIXELS) throw new IOException("Invalid image dimensions or format");
                var parameters = reader.getDefaultReadParam(); parameters.setSourceSubsampling(Math.max(1, width / 512), Math.max(1, height / 512), 0, 0);
                if (reader.read(0, parameters) == null) throw new IOException("Invalid image content");
            } finally { reader.dispose(); }
        }
    }

    static Path resolve(String reference, Path directory) {
        if (reference == null || reference.isBlank()) return null;
        try {
            if (reference.startsWith("images/")) {
                Path path = directory.resolve(reference).normalize();
                return path.startsWith(directory.resolve("images").normalize()) ? path : null;
            }
            Path old = reference.startsWith("file:") ? Path.of(URI.create(reference)) : Path.of(reference);
            // Old releases wrote absolute URIs. Recover relocated managed images by their unique basename.
            String portable = reference.replace('\\', '/');
            if (portable.contains("/images/")) {
                String name = portable.substring(portable.lastIndexOf('/') + 1);
                if (reference.startsWith("file:")) name = URI.create(reference).getPath().substring(URI.create(reference).getPath().lastIndexOf('/') + 1);
                Path relocated = directory.resolve("images").resolve(name);
                if (Files.isRegularFile(relocated)) return relocated;
            }
            if (Files.isRegularFile(old)) return old;
            String name = portable.substring(portable.lastIndexOf('/') + 1);
            Path legacy = Path.of("resources", "img", name);
            return Files.isRegularFile(legacy) ? legacy : old;
        } catch (IllegalArgumentException ex) { return null; }
    }

    static String managedReference(String reference, Path directory) {
        Path resolved = resolve(reference, directory);
        Path images = directory.resolve("images").toAbsolutePath().normalize();
        if (resolved != null && resolved.toAbsolutePath().normalize().startsWith(images)) return "images/" + images.relativize(resolved.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return reference == null ? "" : reference;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString(); int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
