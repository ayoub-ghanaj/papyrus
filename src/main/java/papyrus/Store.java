package papyrus;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** SQLite boundary. All user values are bound parameters; changes and audit entries commit together. */
final class Store implements AutoCloseable {
    enum Entity {
        BOOKS("docs", "doc_id", "doc_libelle", "image", "cat_id", "type_id", "author", "isbn", "shelf", "copy_code"),
        STUDENTS("etudiant", "etud_id", "cne", "nom", "prenom", "phone", "email", "image"),
        STAFF("staff", "staff_id", "cin", "nom", "prenom", "phone", "role", "pass"),
        TYPES("types", "type_id", "ty_libelle"), CATEGORIES("categories", "cat_id", "cat_libelle");
        final String table, id;
        final List<String> fields;
        Entity(String table, String id, String... fields) {
            this.table = table; this.id = id; this.fields = List.of(fields);
        }
    }

    record Row(Map<String, String> values) {
        String text(String key) { return Objects.toString(values.get(key), ""); }
        int number(String key) { return Integer.parseInt(text(key)); }
        @Override public String toString() { return text("label"); }
    }
    record User(int id, String name, boolean admin) {}
    static final class Rule extends RuntimeException {
        final String key;
        Rule(String key) { super(key); this.key = key; }
    }

    static final class ImportFailure extends RuntimeException {
        final int record;
        ImportFailure(int record, Exception cause) { super(cause); this.record = record; }
    }

    private final Connection connection;
    private final ProfileLock profileLock;
    private User user;
    private int authVersion;
    private String restoreEpoch;
    final Path directory;

    Store(Path directory) throws SQLException, IOException {
        this.directory = Files.createDirectories(directory.toAbsolutePath());
        Path database = this.directory.resolve("test1.db");
        boolean existing = Files.isRegularFile(database);
        profileLock = ProfileLock.open(this.directory);
        try { connection = DriverManager.getConnection("jdbc:sqlite:" + database); }
        catch (SQLException ex) { profileLock.close(); throw ex; }
        try (Statement configuration = connection.createStatement()) { configuration.execute("PRAGMA busy_timeout=5000"); }
        try {
            DatabaseMigrations.migrate(connection, this.directory, existing);
        } catch (SQLException | IOException | RuntimeException ex) {
            connection.close(); profileLock.close(); throw ex;
        }
    }

    synchronized boolean needsSetup() throws SQLException { return scalar("SELECT count(*) FROM staff") == 0; }
    synchronized User user() { return user; }
    synchronized void logout() throws SQLException {
        try { if (user != null) { authenticated(); audit("logout"); } }
        finally { user = null; }
    }


    synchronized void setup(String login, String name, String password) throws SQLException {
        if (!needsSetup()) throw new Rule("forbidden");
        required(login, name); checkPassword(password);
        transaction(() -> {
            if (!needsSetup()) throw new Rule("forbidden");
            execute("INSERT INTO staff(cin,nom,prenom,pass,role) VALUES(?,?,?, ?,1)", login.trim(), name.trim(), "", Passwords.hash(password));
            return null;
        });
    }

    synchronized User login(String login, String password) throws SQLException {
        user = null;
        Row account = transaction(() -> {
            List<Row> rows = query("SELECT * FROM staff WHERE status=1 AND cin=?", login.trim());
            if (rows.isEmpty() || !Passwords.verify(password, rows.getFirst().text("pass"))) throw new Rule("invalidLogin");
            Row row = rows.getFirst();
            if (!Passwords.encoded(row.text("pass"))) execute("UPDATE staff SET pass=? WHERE staff_id=?", Passwords.hash(password), row.number("staff_id"));
            execute("INSERT INTO trace(date,info,staff_id) VALUES(?,?,?)", LocalDateTime.now().toString(), "login", row.number("staff_id"));
            restoreEpoch = query("SELECT value FROM app_meta WHERE key='restore_epoch'").getFirst().text("value");
            return row;
        });
        authVersion = account.number("auth_version");
        user = accountUser(account);
        return user;
    }

    synchronized void validateSession() throws SQLException { authenticated(); }

    synchronized void changePassword(String current, String replacement) throws SQLException {
        checkPassword(replacement);
        transaction(() -> {
            authenticated();
            Row row = query("SELECT pass FROM staff WHERE staff_id=?", user.id()).getFirst();
            if (!Passwords.verify(current, row.text("pass"))) throw new Rule("invalidLogin");
            execute("UPDATE staff SET pass=?,auth_version=auth_version+1,revision=revision+1 WHERE staff_id=?", Passwords.hash(replacement), user.id());
            audit("passwordChanged"); return null;
        });
        authVersion++;
    }

    private static User accountUser(Row row) {
        return new User(row.number("staff_id"), (row.text("prenom") + " " + row.text("nom")).trim(), row.number("role") == 1);
    }

    synchronized List<Row> list(Entity entity) throws SQLException { return list(entity, false); }

    synchronized List<Row> list(Entity entity, boolean archived) throws SQLException {
        authorize(entity);
        String sql = switch (entity) {
            case BOOKS -> "SELECT d.*,c.cat_libelle,t.ty_libelle,EXISTS(SELECT 1 FROM emprunts e WHERE e.doc_id=d.doc_id AND e.status=1 AND e.emp_state=1) AS borrowed FROM docs d LEFT JOIN categories c ON c.cat_id=d.cat_id LEFT JOIN types t ON t.type_id=d.type_id WHERE d.status=1 ORDER BY d.doc_id DESC";
            case STAFF -> "SELECT staff_id,cin,nom,prenom,phone,role,status,revision FROM staff WHERE status=1 ORDER BY staff_id DESC";
            default -> "SELECT * FROM " + entity.table + " WHERE status=1 ORDER BY " + entity.id + " DESC";
        };
        if (archived) sql = entity == Entity.BOOKS ? sql.replace("WHERE d.status=1", "WHERE d.status=0") : sql.replace("WHERE status=1", "WHERE status=0");
        return query(sql);
    }

    synchronized List<Row> choices(Entity entity) throws SQLException {
        return list(entity).stream().map(row -> {
            var map = new HashMap<>(row.values());
            String label = switch (entity) {
                case BOOKS -> row.text("doc_libelle") + " · " + (row.text("copy_code").isBlank() ? "#" + row.text("doc_id") : row.text("copy_code"));
                case STUDENTS -> row.text("cne") + " · " + row.text("prenom") + " " + row.text("nom");
                case TYPES -> row.text("ty_libelle");
                case CATEGORIES -> row.text("cat_libelle");
                case STAFF -> row.text("cin");
            };
            map.put("label", label);
            return new Row(map);
        }).toList();
    }

    synchronized int save(Entity entity, Integer id, Map<String, String> input) throws SQLException {
        return transaction(() -> saveRecord(entity, id, input));
    }

    private int saveRecord(Entity entity, Integer id, Map<String, String> input) throws SQLException {
        authorize(entity);
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : entity.fields) values.put(field, Objects.toString(input.get(field), "").trim());
        if (entity.fields.contains("image")) values.put("image", ImageStorage.managedReference(values.get("image"), directory));
        switch (entity) {
            case BOOKS -> {
                required(values.get("doc_libelle"));
                active(Entity.TYPES, values.get("type_id")); active(Entity.CATEGORIES, values.get("cat_id"));
            }
            case STUDENTS -> {
                required(values.get("cne"), values.get("nom"), values.get("prenom"), values.get("phone"));
                if (!values.get("email").isEmpty() && !values.get("email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new Rule("invalidEmail");
            }
            case STAFF -> {
                required(values.get("cin"), values.get("nom"), values.get("prenom"));
                if (!Set.of("1", "2").contains(values.get("role"))) throw new Rule("required");
                String password = input.getOrDefault("pass", "");
                if (id != null && password.isEmpty()) values.remove("pass");
                else { checkPassword(password); values.put("pass", Passwords.hash(password)); }
            }
            case TYPES -> required(values.get("ty_libelle"));
            case CATEGORIES -> required(values.get("cat_libelle"));
        }
        if (id != null) {
            active(entity, id.toString());
            if (input.containsKey("_revision") && !input.get("_revision").equals(query("SELECT revision FROM " + entity.table + " WHERE " + entity.id + "=?", id).getFirst().text("revision"))) throw new Rule("recordChanged");
        }
        if (entity == Entity.STAFF && id != null && !values.get("role").equals("1")) protectAdmin(id);
        List<Object> parameters = new ArrayList<>(values.values());
        if (entity == Entity.STUDENTS && values.get("email").isEmpty()) parameters.set(new ArrayList<>(values.keySet()).indexOf("email"), null);
        int savedId;
        if (id == null) {
            execute("INSERT INTO " + entity.table + "(" + String.join(",", values.keySet()) + ") VALUES(" + String.join(",", Collections.nCopies(values.size(), "?")) + ")", parameters.toArray());
            savedId = scalar("SELECT last_insert_rowid()");
        } else {
            String authentication = "";
            if (entity == Entity.STAFF) {
                String oldRole = query("SELECT role FROM staff WHERE staff_id=?", id).getFirst().text("role");
                if (values.containsKey("pass") || !oldRole.equals(values.get("role"))) authentication = ",auth_version=auth_version+1";
            }
            parameters.add(id);
            execute("UPDATE " + entity.table + " SET " + String.join(",", values.keySet().stream().map(key -> key + "=?").toList()) + ",revision=revision+1" + authentication + " WHERE " + entity.id + "=?", parameters.toArray());
            savedId = id;
        }
        audit((id == null ? "create " : "update ") + entity.table + " #" + savedId);
        return savedId;
    }

    synchronized int importRecords(Entity entity, List<Map<String, String>> records) throws SQLException {
        if (!Set.of(Entity.BOOKS, Entity.STUDENTS).contains(entity)) throw new Rule("forbidden");
        return transaction(() -> {
            authorize(entity);
            int recordNumber = 0;
            for (Map<String, String> input : records) {
                recordNumber++;
                try {
                Map<String, String> values = new LinkedHashMap<>(input);
                if (entity == Entity.BOOKS) {
                    values.put("cat_id", Integer.toString(classification(Entity.CATEGORIES, input.get("category"))));
                    values.put("type_id", Integer.toString(classification(Entity.TYPES, input.get("type"))));
                }
                values.put("image", "");
                saveRecord(entity, null, values);
                } catch (SQLException | Rule ex) { throw new ImportFailure(recordNumber, ex); }
            }
            return records.size();
        });
    }

    private int classification(Entity entity, String label) throws SQLException {
        required(label);
        String field = entity.fields.getFirst();
        List<Row> matches = query("SELECT " + entity.id + " FROM " + entity.table + " WHERE status=1 AND " + field + "=?", label.trim());
        if (matches.size() > 1) throw new Rule("ambiguousClassification");
        return matches.isEmpty() ? saveRecord(entity, null, Map.of(field, label)) : matches.getFirst().number(entity.id);
    }

    synchronized void restore(Entity entity, int id) throws SQLException {
        transaction(() -> {
            authorize(entity);
            List<Row> rows = query("SELECT * FROM " + entity.table + " WHERE " + entity.id + "=? AND status=0", id);
            if (rows.isEmpty()) throw new Rule("notFound");
            if (entity == Entity.BOOKS) {
                active(Entity.CATEGORIES, rows.getFirst().text("cat_id")); active(Entity.TYPES, rows.getFirst().text("type_id"));
            }
            execute("UPDATE " + entity.table + " SET status=1,revision=revision+1" + (entity == Entity.STAFF ? ",auth_version=auth_version+1" : "") + " WHERE " + entity.id + "=?", id);
            audit("restore " + entity.table + " #" + id); return null;
        });
    }

    synchronized void delete(Entity entity, int id) throws SQLException {
        transaction(() -> {
            authorize(entity);
            active(entity, Integer.toString(id));
            if (entity == Entity.STAFF) { if (id == user.id()) throw new Rule("selfDelete"); protectAdmin(id); }
            if ((entity == Entity.BOOKS || entity == Entity.STUDENTS)
                    && scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1 AND " + (entity == Entity.BOOKS ? "doc_id" : "etud_id") + "=?", id) > 0) throw new Rule("activeLoans");
            if ((entity == Entity.TYPES || entity == Entity.CATEGORIES)
                    && scalar("SELECT count(*) FROM docs WHERE status=1 AND " + (entity == Entity.TYPES ? "type_id" : "cat_id") + "=?", id) > 0) throw new Rule("inUse");
            execute("UPDATE " + entity.table + " SET status=0,revision=revision+1" + (entity == Entity.STAFF ? ",auth_version=auth_version+1" : "") + " WHERE " + entity.id + "=?", id);
            audit("delete " + entity.table + " #" + id);
            return null;
        });
    }

    synchronized List<Row> loans(boolean history, Entity entity, Integer id) throws SQLException { return loans(history, entity, id, 0); }

    synchronized List<Row> loans(boolean history, Entity entity, Integer id, int limit) throws SQLException {
        authenticated();
        String filter = history ? "" : " AND e.emp_state=1";
        if (entity != null && id != null) {
            if (entity == Entity.STAFF && !user.admin()) throw new Rule("forbidden");
            filter += " AND e." + switch (entity) { case BOOKS -> "doc_id"; case STUDENTS -> "etud_id"; case STAFF -> "staff_id"; default -> throw new Rule("forbidden"); } + "=?";
        }
        String sql = "SELECT e.*,d.doc_libelle,s.cne,s.phone,s.email,d.copy_code,s.prenom||' '||s.nom AS student,f.cin FROM emprunts e JOIN docs d ON d.doc_id=e.doc_id JOIN etudiant s ON s.etud_id=e.etud_id JOIN staff f ON f.staff_id=e.staff_id WHERE e.status=1" + filter + " ORDER BY e.emp_id DESC";
        if (limit > 0) sql += " LIMIT " + limit;
        return id == null ? query(sql) : query(sql, id);
    }

    synchronized void borrow(int book, int student, LocalDate due) throws SQLException {
        authenticated();
        if (due == null || !due.isAfter(LocalDate.now())) throw new Rule("invalidDate");
        transaction(() -> {
            authenticated();
            active(Entity.BOOKS, Integer.toString(book)); active(Entity.STUDENTS, Integer.toString(student));
            if (scalar("SELECT count(*) FROM emprunts WHERE doc_id=? AND emp_state=1 AND status=1", book) > 0) throw new Rule("alreadyBorrowed");
            execute("INSERT INTO emprunts(doc_id,etud_id,staff_id,emp_d,return_d) VALUES(?,?,?,?,?)", book, student, user.id(), LocalDate.now().toString(), due.toString());
            audit("borrow docs #" + book + " etudiant #" + student);
            return null;
        });
    }

    synchronized void returnLoan(int id) throws SQLException {
        authenticated();
        transaction(() -> {
            authenticated();
            if (execute("UPDATE emprunts SET emp_state=0,returned_at=?,returned_by=? WHERE emp_id=? AND status=1 AND emp_state=1", LocalDateTime.now().toString(), user.id(), id) != 1) throw new Rule("notFound");
            audit("return emprunts #" + id);
            return null;
        });
    }

    synchronized void renewLoan(int id, LocalDate due) throws SQLException {
        if (due == null || !due.isAfter(LocalDate.now())) throw new Rule("invalidDate");
        transaction(() -> {
            authenticated();
            List<Row> rows = query("SELECT return_d FROM emprunts WHERE emp_id=? AND status=1 AND emp_state=1", id);
            if (rows.isEmpty()) throw new Rule("notFound");
            String previous = rows.getFirst().text("return_d");
            if (!due.isAfter(LocalDate.parse(previous))) throw new Rule("renewalDate");
            execute("UPDATE emprunts SET return_d=?,renewals=renewals+1 WHERE emp_id=?", due.toString(), id);
            audit("renew emprunts #" + id + " " + previous + " → " + due); return null;
        });
    }

    synchronized AutoCloseable exclusiveRestore() throws SQLException, IOException { admin(); return profileLock.exclusive(); }

    synchronized void snapshot(Path destination) throws SQLException { admin(); DatabaseMigrations.snapshot(connection, destination); }
    synchronized void requireAdmin() throws SQLException { admin(); }

    synchronized void restoreSnapshot(Path snapshot) throws SQLException {
        admin();
        execute("ATTACH DATABASE ? AS restore_source", snapshot.toAbsolutePath().toString());
        try {
            transaction(() -> {
                admin();
                for (String table : List.of("trace", "emprunts", "docs", "etudiant", "types", "categories", "staff", "app_meta")) execute("DELETE FROM main." + table);
                for (String table : List.of("staff", "types", "categories", "etudiant", "docs", "emprunts", "trace", "app_meta")) {
                    List<String> columns = query("PRAGMA main.table_info(" + table + ")").stream().map(row -> row.text("name")).toList();
                    String fields = String.join(",", columns);
                    execute("INSERT INTO main." + table + "(" + fields + ") SELECT " + fields + " FROM restore_source." + table);
                }
                execute("UPDATE app_meta SET value=? WHERE key='restore_epoch'", UUID.randomUUID().toString());
                execute("INSERT INTO trace(date,info,staff_id) VALUES(?,?,NULL)", LocalDateTime.now().toString(), "backupRestored");
                return null;
            });
            user = null;
        } finally { execute("DETACH DATABASE restore_source"); }
    }

    synchronized List<Row> auditLog(Integer staff) throws SQLException {
        admin();
        String sql = "SELECT t.*,s.cin FROM trace t LEFT JOIN staff s ON s.staff_id=t.staff_id";
        return staff == null ? query(sql + " ORDER BY t.trace DESC LIMIT 1000") : query(sql + " WHERE t.staff_id=? ORDER BY t.trace DESC LIMIT 1000", staff);
    }

    synchronized Map<String, Integer> statistics() throws SQLException {
        authenticated();
        return Map.of("books", scalar("SELECT count(*) FROM docs WHERE status=1"), "students", scalar("SELECT count(*) FROM etudiant WHERE status=1"),
                "loans", scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1"),
                "overdue", scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1 AND return_d<?", LocalDate.now().toString()));
    }

    synchronized List<Row> popularBooks() throws SQLException {
        authenticated();
        return query("SELECT d.doc_libelle,count(*) AS total FROM emprunts e JOIN docs d ON d.doc_id=e.doc_id WHERE e.status=1 GROUP BY d.doc_id ORDER BY total DESC LIMIT 6");
    }

    private void protectAdmin(int id) throws SQLException {
        if (scalar("SELECT count(*) FROM staff WHERE staff_id=? AND role=1 AND status=1", id) > 0
                && scalar("SELECT count(*) FROM staff WHERE role=1 AND status=1") <= 1) throw new Rule("lastAdmin");
        if (id == user.id()) throw new Rule("selfDelete");
    }
    private void active(Entity entity, String id) throws SQLException {
        if (id.isBlank() || scalar("SELECT count(*) FROM " + entity.table + " WHERE " + entity.id + "=? AND status=1", id) == 0) throw new Rule("notFound");
    }
    private void authenticated() throws SQLException {
        if (user == null) throw new Rule("forbidden");
        List<Row> rows = query("SELECT staff_id,nom,prenom,role,auth_version FROM staff WHERE staff_id=? AND status=1", user.id());
        String epoch = query("SELECT value FROM app_meta WHERE key='restore_epoch'").getFirst().text("value");
        if (rows.isEmpty() || rows.getFirst().number("auth_version") != authVersion || !Objects.equals(epoch, restoreEpoch)) {
            user = null; throw new Rule("sessionExpired");
        }
        user = accountUser(rows.getFirst());
    }
    private void admin() throws SQLException { authenticated(); if (!user.admin()) throw new Rule("forbidden"); }
    private void authorize(Entity entity) throws SQLException { authenticated(); if (entity == Entity.STAFF) admin(); }
    private static void required(String... values) { for (String value : values) if (value == null || value.isBlank()) throw new Rule("required"); }
    private static void checkPassword(String password) { if (password.length() < 8) throw new Rule("passwordLength"); }
    private void audit(String action) throws SQLException { execute("INSERT INTO trace(date,info,staff_id) VALUES(?,?,?)", LocalDateTime.now().toString(), action, user.id()); }

    private List<Row> query(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                List<Row> rows = new ArrayList<>();
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) values.put(result.getMetaData().getColumnLabel(i), result.getString(i));
                    rows.add(new Row(Collections.unmodifiableMap(values)));
                }
                return rows;
            }
        }
    }
    private int scalar(String sql, Object... parameters) throws SQLException { return Integer.parseInt(query(sql, parameters).getFirst().values().values().iterator().next()); }
    private int execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, parameters); return statement.executeUpdate(); }
    }
    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException { for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]); }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        // IMMEDIATE reserves the write lock before testing availability across running instances.
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try { T result = work.run(); statement.execute("COMMIT"); return result; }
            catch (SQLException | RuntimeException ex) { statement.execute("ROLLBACK"); throw ex; }
        }
    }
    @Override public synchronized void close() throws SQLException {
        try { connection.close(); } finally { try { profileLock.close(); } catch (IOException ex) { throw new SQLException("Could not release library lock", ex); } }
    }
}
