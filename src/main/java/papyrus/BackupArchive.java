package papyrus;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/** Portable, verified SQLite snapshots. Restoring keeps a recovery archive of the current library. */
final class BackupArchive {
    private static final long MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 25000;
    private static final Set<String> TABLES = Set.of("staff", "types", "categories", "etudiant", "docs", "emprunts", "trace", "app_meta");

    static void create(Store store, Path destination) throws IOException, SQLException {
        store.requireAdmin();
        Path work = Files.createTempDirectory("papyrus-backup-");
        Path output = null;
        try {
            Path database = work.resolve("test1.db");
            store.snapshot(database);
            Properties manifest = new Properties(); manifest.setProperty("format", "1");
            Map<Path, String> copiedImages = new HashMap<>(); long[] copiedBytes = {Files.size(database)};
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                for (String table : List.of("docs", "etudiant")) {
                    String id = table.equals("docs") ? "doc_id" : "etud_id";
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT " + id + ",image FROM " + table + " WHERE image IS NOT NULL AND image<>''")) {
                        while (rows.next()) {
                            Path source = ImageStorage.resolve(rows.getString("image"), store.directory);
                            if (source == null || !Files.isRegularFile(source)) throw new IOException("Missing image: " + rows.getString("image"));
                            Path canonical = source.toRealPath(); String relative = copiedImages.get(canonical);
                            if (relative == null) {
                                copiedBytes[0] += Files.size(source);
                                if (copiedBytes[0] > MAX_EXPANDED_BYTES || copiedImages.size() + 2 >= MAX_ENTRIES) throw new IOException("Backup exceeds supported archive limits");
                                relative = ImageStorage.importImage(source, work); copiedImages.put(canonical, relative);
                            }
                            try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET image=? WHERE " + id + "=?")) {
                                update.setString(1, relative); update.setInt(2, rows.getInt(id)); update.executeUpdate();
                            }
                        }
                    }
                }
            }
            List<Path> files;
            try (var paths = Files.walk(work)) { files = paths.filter(Files::isRegularFile).toList(); }
            long total = 0;
            for (Path file : files) {
                total += Files.size(file);
                manifest.setProperty("sha256." + work.relativize(file).toString().replace('\\', '/'), digest(file));
            }
            if (total > MAX_EXPANDED_BYTES || files.size() >= MAX_ENTRIES) throw new IOException("Backup exceeds supported archive limits");
            try (OutputStream stream = Files.newOutputStream(work.resolve("manifest.properties"))) { manifest.store(stream, "Papyrus backup"); }
            Path parent = destination.toAbsolutePath().getParent(); Files.createDirectories(parent);
            output = Files.createTempFile(parent, ".papyrus-backup-", ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
                List<Path> entries = new ArrayList<>(files); entries.add(work.resolve("manifest.properties"));
                for (Path file : entries) {
                    zip.putNextEntry(new ZipEntry(work.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip); zip.closeEntry();
                }
            }
            Files.move(output, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (output != null) Files.deleteIfExists(output); removeTree(work); }
    }

    static Path restore(Store store, Path archive) throws IOException, SQLException {
        store.requireAdmin();
        Path work = Files.createTempDirectory("papyrus-restore-");
        List<Path> imported = new ArrayList<>();
        boolean committed = false;
        try (AutoCloseable exclusive = store.exclusiveRestore()) {
            extract(archive, work);
            validate(work);
            Path snapshot = work.resolve("test1.db");
            validateDatabase(snapshot);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path recovery = store.directory.resolve("backups").resolve("before-restore-" + stamp + "-" + UUID.randomUUID() + ".zip");
            create(store, recovery);
            Map<Path, String> restoredImages = new HashMap<>();
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot)) {
                for (String table : List.of("docs", "etudiant")) {
                    String id = table.equals("docs") ? "doc_id" : "etud_id";
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT " + id + ",image FROM " + table + " WHERE image IS NOT NULL AND image<>''")) {
                        while (rows.next()) {
                            Path source = imageInArchive(work, rows.getString("image"));
                            String relative = restoredImages.get(source);
                            if (relative == null) {
                                relative = ImageStorage.importImage(source, store.directory); restoredImages.put(source, relative); imported.add(store.directory.resolve(relative));
                            }
                            try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET image=? WHERE " + id + "=?")) {
                                update.setString(1, relative); update.setInt(2, rows.getInt(id)); update.executeUpdate();
                            }
                        }
                    }
                }
            }
            store.restoreSnapshot(snapshot); committed = true;
            return recovery;
        } catch (IOException | SQLException | RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("Could not release restore lock", ex); }
        finally {
            if (!committed) for (Path file : imported) Files.deleteIfExists(file);
            removeTree(work);
        }
    }

    private static void extract(Path archive, Path work) throws IOException {
        long total = 0; int count = 0; Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] bytes = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++count > MAX_ENTRIES || !names.add(name) || entry.isDirectory() || name.contains("\\") || name.contains(":") || name.startsWith("/")
                        || !(name.equals("test1.db") || name.equals("manifest.properties") || name.matches("images/[a-zA-Z0-9.-]+"))) throw new IOException("Invalid backup entry");
                Path target = work.resolve(name).normalize();
                if (!target.startsWith(work)) throw new IOException("Invalid backup path");
                Files.createDirectories(target.getParent());
                long entryBytes = 0;
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    int size;
                    while ((size = zip.read(bytes)) != -1) {
                        total += size; entryBytes += size;
                        if (total > MAX_EXPANDED_BYTES || (name.startsWith("images/") && entryBytes > ImageStorage.MAX_BYTES) || (name.equals("manifest.properties") && entryBytes > 8 * 1024 * 1024)) throw new IOException("Backup exceeds supported archive limits");
                        output.write(bytes, 0, size);
                    }
                }
            }
        }
    }

    private static void validate(Path work) throws IOException {
        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(work.resolve("manifest.properties"))) { manifest.load(input); }
        if (!"1".equals(manifest.getProperty("format"))) throw new IOException("Unsupported backup format");
        Set<String> found = new HashSet<>();
        try (var paths = Files.walk(work)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = work.relativize(path).toString().replace('\\', '/');
                if (name.equals("manifest.properties")) continue;
                String key = "sha256." + name; found.add(key);
                if (!digest(path).equals(manifest.getProperty(key))) throw new IOException("Backup checksum mismatch: " + name);
            }
        }
        if (!found.contains("sha256.test1.db") || manifest.stringPropertyNames().stream().filter(k -> k.startsWith("sha256.")).anyMatch(k -> !found.contains(k))) throw new IOException("Incomplete backup");
    }

    private static void validateDatabase(Path database) throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA trusted_schema=OFF");
            try (ResultSet result = sql.executeQuery("PRAGMA quick_check")) { if (!result.next() || !"ok".equals(result.getString(1))) throw new IOException("Invalid backup database"); }
            try (ResultSet result = sql.executeQuery("PRAGMA user_version")) { if (!result.next() || result.getInt(1) != DatabaseMigrations.VERSION) throw new IOException("Unsupported backup database version"); }
            Set<String> tables = new HashSet<>();
            try (ResultSet result = sql.executeQuery("SELECT name,type FROM sqlite_master WHERE type IN ('table','view','trigger') AND name NOT LIKE 'sqlite_%'")) {
                while (result.next()) {
                    if (!"table".equals(result.getString("type")) || !TABLES.contains(result.getString("name"))) throw new IOException("Unexpected backup schema");
                    tables.add(result.getString("name"));
                }
            }
            if (!tables.equals(TABLES)) throw new IOException("Incomplete backup schema");
            try (ResultSet result = sql.executeQuery("SELECT count(*) FROM staff WHERE status=1 AND role=1")) { if (!result.next() || result.getInt(1) < 1) throw new IOException("Backup has no active administrator"); }
            for (String table : List.of("docs", "etudiant")) {
                try (ResultSet result = sql.executeQuery("SELECT image FROM " + table + " WHERE image IS NOT NULL AND image<>''")) {
                    while (result.next()) ImageStorage.validate(imageInArchive(database.getParent(), result.getString(1)));
                }
            }
        }
    }

    private static Path imageInArchive(Path work, String name) throws IOException {
        if (!name.matches("images/[a-zA-Z0-9.-]+")) throw new IOException("Invalid backup image reference");
        Path image = work.resolve(name).normalize();
        if (!image.getParent().equals(work.resolve("images")) || !Files.isRegularFile(image)) throw new IOException("Missing backup image");
        return image;
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) { byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void removeTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
