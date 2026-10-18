package papyrus;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path directory;
    Store store;
    @BeforeEach void open() throws Exception {
        store = new Store(directory);
        store.setup("owner", "Owner", "correct-password");
        store.login("owner", "correct-password");
    }
    @AfterEach void close() throws Exception { store.close(); }

    private int add(Store.Entity entity, Map<String, String> values) throws Exception {
        store.save(entity, null, values);
        return store.list(entity).getFirst().number(entity.id);
    }
    private int book(String title) throws Exception {
        int category = add(Store.Entity.CATEGORIES, Map.of("cat_libelle", "أدب / Literature"));
        int type = add(Store.Entity.TYPES, Map.of("ty_libelle", "Livre"));
        return add(Store.Entity.BOOKS, Map.of("doc_libelle", title, "cat_id", "" + category, "type_id", "" + type));
    }
    private int student() throws Exception {
        return add(Store.Entity.STUDENTS, Map.of("cne", "S-1", "nom", "O'Neil", "prenom", "أمين", "phone", "+212600000001"));
    }
    private void rule(String key, org.junit.jupiter.api.function.Executable action) {
        assertEquals(key, assertThrows(Store.Rule.class, action).key);
    }

    @Test void unicodeApostrophesAndSqlPayloadRemainData() throws Exception {
        String title = "كتاب O'Reilly'); DROP TABLE docs; --";
        int id = book(title);
        Store.Row row = store.list(Store.Entity.BOOKS).getFirst();
        assertEquals(title, row.text("doc_libelle"));
        store.save(Store.Entity.BOOKS, id, Map.of("doc_libelle", "Updated ' العربية", "cat_id", row.text("cat_id"), "type_id", row.text("type_id")));
        assertEquals("Updated ' العربية", store.list(Store.Entity.BOOKS).getFirst().text("doc_libelle"));
        assertEquals(1, store.statistics().get("books"));
    }

    @Test void loansReturnsAndSoftDeletesRetainHistoryAndRealTotals() throws Exception {
        int book = book("A book"); int student = student();
        store.borrow(book, student, LocalDate.now().plusDays(10));
        assertEquals(1, store.statistics().get("loans"));
        assertEquals("1", store.list(Store.Entity.BOOKS).getFirst().text("borrowed"));
        rule("activeLoans", () -> store.delete(Store.Entity.BOOKS, book));
        rule("activeLoans", () -> store.delete(Store.Entity.STUDENTS, student));
        int loan = store.loans(false, null, null).getFirst().number("emp_id");
        store.returnLoan(loan);
        rule("notFound", () -> store.returnLoan(loan));
        store.delete(Store.Entity.BOOKS, book); store.delete(Store.Entity.STUDENTS, student);
        assertEquals(0, store.statistics().get("books"));
        assertEquals(0, store.statistics().get("students"));
        assertEquals(0, store.statistics().get("loans"));
        assertEquals(1, store.loans(true, Store.Entity.BOOKS, book).size());
        assertEquals("0", store.loans(true, null, null).getFirst().text("emp_state"));
    }

    @Test void duplicateLoansRejectedAcrossConnectionsAndDatesValidated() throws Exception {
        int book = book("Concurrency"); int student = student();
        rule("invalidDate", () -> store.borrow(book, student, LocalDate.now()));
        try (Store other = new Store(directory)) {
            other.login("owner", "correct-password");
            store.borrow(book, student, LocalDate.now().plusDays(1));
            rule("alreadyBorrowed", () -> other.borrow(book, student, LocalDate.now().plusDays(2)));
        }
        assertEquals(1, store.loans(true, null, null).size());
    }

    @Test void rolesAreEnforcedAndStaffIdIsRecorded() throws Exception {
        int book = book("Borrowed by staff"); int student = student();
        int staff = add(Store.Entity.STAFF, Map.of("cin", "librarian", "nom", "N", "prenom", "P", "role", "2", "pass", "staff-password"));
        assertFalse(store.list(Store.Entity.STAFF).getFirst().values().containsKey("pass"));
        store.logout(); store.login("librarian", "staff-password");
        assertFalse(store.user().admin()); assertEquals(staff, store.user().id());
        rule("forbidden", () -> store.list(Store.Entity.STAFF));
        rule("forbidden", () -> store.save(Store.Entity.STAFF, null, Map.of()));
        rule("forbidden", () -> store.auditLog(null));
        store.borrow(book, student, LocalDate.now().plusDays(2));
        assertEquals(staff, store.loans(false, null, null).getFirst().number("staff_id"));
        store.logout(); store.login("owner", "correct-password");
        assertTrue(store.auditLog(staff).stream().anyMatch(row -> row.text("info").startsWith("borrow")));
    }

    @Test void referencesAndLastAdminAreProtected() throws Exception {
        book("References");
        int type = store.list(Store.Entity.TYPES).getFirst().number("type_id");
        rule("inUse", () -> store.delete(Store.Entity.TYPES, type));
        rule("selfDelete", () -> store.delete(Store.Entity.STAFF, store.user().id()));
        rule("lastAdmin", () -> store.save(Store.Entity.STAFF, store.user().id(), Map.of("cin", "owner", "nom", "Owner", "prenom", "Admin", "role", "2")));
        rule("forbidden", () -> store.setup("second", "Second", "another-password"));
    }

    @Test void duplicateFailureDoesNotAddAuditOrPartialRow() throws Exception {
        student(); int before = store.auditLog(null).size();
        assertThrows(SQLException.class, this::student);
        assertEquals(1, store.list(Store.Entity.STUDENTS).size());
        assertEquals(before, store.auditLog(null).size());
    }

    @Test void reopeningKeepsDataAndLegacyPasswordsUpgrade() throws Exception {
        int id = book("Persistent");
        store.close();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test1.db")); Statement sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE staff SET pass='legacy' WHERE cin='owner'");
            sql.execute("PRAGMA user_version=0");
        }
        store = new Store(directory);
        assertTrue(Files.isRegularFile(directory.resolve("test1.pre-1.0.0.db")));
        assertFalse(store.needsSetup());
        rule("invalidLogin", () -> store.login("owner", "wrong"));
        store.login("owner", "legacy");
        assertEquals(id, store.list(Store.Entity.BOOKS).getFirst().number("doc_id"));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test1.db")); Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery("SELECT pass FROM staff WHERE cin='owner'")) {
            assertTrue(result.next()); assertTrue(result.getString(1).startsWith("pbkdf2-sha256$"));
        }
    }

    @Test void passwordsUseSaltAndWhitespaceIsSignificant() {
        String hash = Passwords.hash("  password  ");
        assertTrue(Passwords.verify("  password  ", hash));
        assertFalse(Passwords.verify("password", hash));
        assertNotEquals(hash, Passwords.hash("  password  "));
        assertFalse(Passwords.verify("x", "pbkdf2-sha256$broken"));
    }

    @Test void bundlesHaveIdenticalCompleteKeys() {
        ResourceBundle english = ResourceBundle.getBundle("papyrus.messages", Locale.ENGLISH);
        for (String language : List.of("fr", "en", "ar")) {
            I18n i18n = new I18n(language);
            ResourceBundle bundle = ResourceBundle.getBundle("papyrus.messages", Locale.forLanguageTag(language));
            assertEquals(english.keySet(), bundle.keySet());
            for (String key : english.keySet()) assertFalse(i18n.text(key).isBlank(), key);
            assertEquals(language.equals("ar"), i18n.rtl());
        }
    }

    @Test void searchFindsAccentsArabicIdentifiersAndContactDetails() {
        Store.Row row = new Store.Row(Map.of("label", "Émilie · أَمِين", "cne", "ST-002", "phone", "+212612345678", "email", "reader@example.test", "image", "private-image-file", "pass", "hidden-password"));
        assertTrue(SearchText.matches(row, "emilie"));
        assertTrue(SearchText.matches(row, "امين"));
        assertTrue(SearchText.matches(row, "ST-002 امين"));
        assertTrue(SearchText.matches(row, "6123456"));
        assertTrue(SearchText.matches(row, "reader@"));
        assertFalse(SearchText.matches(row, "ST-003"));
        assertFalse(SearchText.matches(row, "hidden-password"));
        assertFalse(SearchText.matches(row, "private-image-file"));
    }
}
