package papyrus;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Run against the installed old and new JARs, with a dedicated CI library. */
public final class UpgradeProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        try (Store store = new Store(directory)) {
            if (args[0].equals("seed")) {
                store.setup("upgrade-test", "Installer test", "upgrade-test-password"); store.login("upgrade-test", "upgrade-test-password");
                save(store, Store.Entity.CATEGORIES, Map.of("cat_libelle", "Category"));
                save(store, Store.Entity.TYPES, Map.of("ty_libelle", "Book"));
                save(store, Store.Entity.STUDENTS, Map.of("cne", "UPGRADE-1", "nom", "Test", "prenom", "Reader", "phone", "000000001"));
                String image = AppPaths.importImage(Path.of("packaging/papyrus.png"), directory);
                save(store, Store.Entity.BOOKS, Map.of("doc_libelle", "Upgrade / ترقية", "cat_id", "1", "type_id", "1", "image", image));
                store.borrow(1, 1, LocalDate.now().plusDays(7));
                Files.writeString(directory.resolve("user-kept.txt"), "Keep this during upgrades and uninstall");
            } else {
                store.login("upgrade-test", "upgrade-test-password");
                var row = store.list(Store.Entity.BOOKS).getFirst();
                check(row.number("doc_id") == 1 && row.text("doc_libelle").equals("Upgrade / ترقية"), "Book and ID survive upgrade");
                check(store.loans(false, null, null).size() == 1, "Active loan survives upgrade");
                check(Files.isRegularFile(ImageStorage.resolve(row.text("image"), directory)), "Image survives upgrade");
                check(Files.isRegularFile(directory.resolve("test1.pre-1.1.0.db")), "Migration backup exists");
                check(Files.readString(directory.resolve("user-kept.txt")).startsWith("Keep"), "User files preserved");
            }
        }
        System.out.println("Installed application upgrade probe passed: " + args[0]);
    }
    private static void save(Store store, Store.Entity entity, Map<String, String> values) throws Exception {
        // Reflection permits the same probe to exercise 1.0.0's void-returning save method.
        Store.class.getDeclaredMethod("save", Store.Entity.class, Integer.class, Map.class).invoke(store, entity, null, values);
    }
    private static void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
}
