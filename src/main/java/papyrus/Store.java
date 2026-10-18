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
        BOOKS("docs", "doc_id", "doc_libelle", "image", "cat_id", "type_id"),
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

    private final Connection connection;
    private User user;
    final Path directory;

    Store(Path directory) throws SQLException, IOException {
        this.directory = Files.createDirectories(directory.toAbsolutePath());
        Path database = this.directory.resolve("test1.db");
        boolean existing = Files.isRegularFile(database);
        connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement configuration = connection.createStatement()) { configuration.execute("PRAGMA busy_timeout=5000"); }
        try (Statement statement = connection.createStatement()) {
            int schemaVersion = scalar("PRAGMA user_version");
            if (schemaVersion > 10000) throw new SQLException("Database was created by a newer Papyrus version");
            if (existing && schemaVersion == 0 && !Files.exists(this.directory.resolve("test1.pre-1.0.0.db"))) {
                try (PreparedStatement backup = connection.prepareStatement("VACUUM INTO ?")) {
                    backup.setString(1, this.directory.resolve("test1.pre-1.0.0.db").toString());
                    backup.execute();
                }
            }
            // Retain the original tables, IDs, soft-delete states and relationships.
            statement.execute("CREATE TABLE IF NOT EXISTS types(type_id INTEGER PRIMARY KEY AUTOINCREMENT,ty_libelle TEXT,status INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS categories(cat_id INTEGER PRIMARY KEY AUTOINCREMENT,cat_libelle TEXT,status INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS docs(doc_id INTEGER PRIMARY KEY AUTOINCREMENT,doc_libelle TEXT,status INTEGER DEFAULT 1,image TEXT,cat_id INTEGER,type_id INTEGER)");
            statement.execute("CREATE TABLE IF NOT EXISTS etudiant(etud_id INTEGER PRIMARY KEY AUTOINCREMENT,cne TEXT NOT NULL UNIQUE,nom TEXT,prenom TEXT,image TEXT,phone TEXT NOT NULL UNIQUE,email TEXT UNIQUE,status INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS staff(staff_id INTEGER PRIMARY KEY AUTOINCREMENT,cin TEXT NOT NULL UNIQUE,nom TEXT,prenom TEXT,phone TEXT,role INTEGER DEFAULT 2,pass TEXT NOT NULL,status INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS emprunts(emp_id INTEGER PRIMARY KEY AUTOINCREMENT,doc_id INTEGER,etud_id INTEGER,staff_id INTEGER,emp_state INTEGER DEFAULT 1,emp_d TEXT,return_d TEXT,status INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE IF NOT EXISTS trace(trace INTEGER PRIMARY KEY AUTOINCREMENT,date TEXT,info TEXT,staff_id INTEGER)");
            statement.execute("PRAGMA user_version=10000");
        } catch (SQLException | RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    boolean needsSetup() throws SQLException { return scalar("SELECT count(*) FROM staff") == 0; }
    User user() { return user; }
    void logout() throws SQLException { if (user != null) audit("logout"); user = null; }

    void setup(String login, String name, String password) throws SQLException {
        if (!needsSetup()) throw new Rule("forbidden");
        required(login, name); checkPassword(password);
        transaction(() -> {
            if (!needsSetup()) throw new Rule("forbidden");
            execute("INSERT INTO staff(cin,nom,prenom,pass,role) VALUES(?,?,?, ?,1)", login.trim(), name.trim(), "", Passwords.hash(password));
            return null;
        });
    }

    User login(String login, String password) throws SQLException {
        List<Row> rows = query("SELECT * FROM staff WHERE status=1 AND cin=?", login.trim());
        if (rows.isEmpty() || !Passwords.verify(password, rows.getFirst().text("pass"))) throw new Rule("invalidLogin");
        Row row = rows.getFirst();
        user = new User(row.number("staff_id"), (row.text("prenom") + " " + row.text("nom")).trim(), row.number("role") == 1);
        transaction(() -> {
            if (!Passwords.encoded(row.text("pass"))) execute("UPDATE staff SET pass=? WHERE staff_id=?", Passwords.hash(password), user.id());
            audit("login");
            return null;
        });
        return user;
    }

    List<Row> list(Entity entity) throws SQLException {
        authorize(entity);
        String sql = switch (entity) {
            case BOOKS -> "SELECT d.*,c.cat_libelle,t.ty_libelle,EXISTS(SELECT 1 FROM emprunts e WHERE e.doc_id=d.doc_id AND e.status=1 AND e.emp_state=1) AS borrowed FROM docs d LEFT JOIN categories c ON c.cat_id=d.cat_id LEFT JOIN types t ON t.type_id=d.type_id WHERE d.status=1 ORDER BY d.doc_id DESC";
            case STAFF -> "SELECT staff_id,cin,nom,prenom,phone,role,status FROM staff WHERE status=1 ORDER BY staff_id DESC";
            default -> "SELECT * FROM " + entity.table + " WHERE status=1 ORDER BY " + entity.id + " DESC";
        };
        return query(sql);
    }

    List<Row> choices(Entity entity) throws SQLException {
        return list(entity).stream().map(row -> {
            var map = new HashMap<>(row.values());
            String label = switch (entity) {
                case BOOKS -> row.text("doc_libelle") + " · #" + row.text("doc_id");
                case STUDENTS -> row.text("cne") + " · " + row.text("prenom") + " " + row.text("nom");
                case TYPES -> row.text("ty_libelle");
                case CATEGORIES -> row.text("cat_libelle");
                case STAFF -> row.text("cin");
            };
            map.put("label", label);
            return new Row(map);
        }).toList();
    }

    void save(Entity entity, Integer id, Map<String, String> input) throws SQLException {
        authorize(entity);
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : entity.fields) values.put(field, input.getOrDefault(field, "").trim());
        switch (entity) {
            case BOOKS -> { required(values.get("doc_libelle")); active(Entity.TYPES, values.get("type_id")); active(Entity.CATEGORIES, values.get("cat_id")); }
            case STUDENTS -> required(values.get("cne"), values.get("nom"), values.get("prenom"), values.get("phone"));
            case STAFF -> {
                required(values.get("cin"), values.get("nom"), values.get("prenom"));
                if (!Set.of("1", "2").contains(values.get("role"))) throw new Rule("required");
                // Passwords are never returned to the UI. Blank on edit keeps the stored value.
                String password = input.getOrDefault("pass", "");
                if (id != null && password.isEmpty()) values.remove("pass");
                else { checkPassword(password); values.put("pass", Passwords.hash(password)); }
            }
            case TYPES -> required(values.get("ty_libelle"));
            case CATEGORIES -> required(values.get("cat_libelle"));
        }
        transaction(() -> {
            if (id != null) active(entity, id.toString());
            if (entity == Entity.STAFF && id != null && !values.get("role").equals("1")) protectAdmin(id);
            List<Object> parameters = new ArrayList<>(values.values());
            // Legacy UNIQUE email permits NULL for people without an email address.
            if (entity == Entity.STUDENTS && values.get("email").isEmpty()) parameters.set(new ArrayList<>(values.keySet()).indexOf("email"), null);
            if (id == null) {
                execute("INSERT INTO " + entity.table + "(" + String.join(",", values.keySet()) + ") VALUES(" + String.join(",", Collections.nCopies(values.size(), "?")) + ")", parameters.toArray());
            } else {
                parameters.add(id);
                execute("UPDATE " + entity.table + " SET " + String.join(",", values.keySet().stream().map(key -> key + "=?").toList()) + " WHERE " + entity.id + "=?", parameters.toArray());
            }
            audit((id == null ? "create " : "update ") + entity.table + (id == null ? "" : " #" + id));
            return null;
        });
    }

    void delete(Entity entity, int id) throws SQLException {
        authorize(entity);
        transaction(() -> {
            active(entity, Integer.toString(id));
            if (entity == Entity.STAFF) { if (id == user.id()) throw new Rule("selfDelete"); protectAdmin(id); }
            if ((entity == Entity.BOOKS || entity == Entity.STUDENTS)
                    && scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1 AND " + (entity == Entity.BOOKS ? "doc_id" : "etud_id") + "=?", id) > 0) throw new Rule("activeLoans");
            if ((entity == Entity.TYPES || entity == Entity.CATEGORIES)
                    && scalar("SELECT count(*) FROM docs WHERE status=1 AND " + (entity == Entity.TYPES ? "type_id" : "cat_id") + "=?", id) > 0) throw new Rule("inUse");
            execute("UPDATE " + entity.table + " SET status=0 WHERE " + entity.id + "=?", id);
            audit("delete " + entity.table + " #" + id);
            return null;
        });
    }

    List<Row> loans(boolean history, Entity entity, Integer id) throws SQLException {
        authenticated();
        String filter = history ? "" : " AND e.emp_state=1";
        if (entity != null && id != null) {
            if (entity == Entity.STAFF && !user.admin()) throw new Rule("forbidden");
            filter += " AND e." + switch (entity) { case BOOKS -> "doc_id"; case STUDENTS -> "etud_id"; case STAFF -> "staff_id"; default -> throw new Rule("forbidden"); } + "=?";
        }
        String sql = "SELECT e.*,d.doc_libelle,s.cne,s.prenom||' '||s.nom AS student,f.cin FROM emprunts e JOIN docs d ON d.doc_id=e.doc_id JOIN etudiant s ON s.etud_id=e.etud_id JOIN staff f ON f.staff_id=e.staff_id WHERE e.status=1" + filter + " ORDER BY e.emp_id DESC";
        return id == null ? query(sql) : query(sql, id);
    }

    void borrow(int book, int student, LocalDate due) throws SQLException {
        authenticated();
        if (due == null || !due.isAfter(LocalDate.now())) throw new Rule("invalidDate");
        transaction(() -> {
            active(Entity.BOOKS, Integer.toString(book)); active(Entity.STUDENTS, Integer.toString(student));
            if (scalar("SELECT count(*) FROM emprunts WHERE doc_id=? AND emp_state=1 AND status=1", book) > 0) throw new Rule("alreadyBorrowed");
            execute("INSERT INTO emprunts(doc_id,etud_id,staff_id,emp_d,return_d) VALUES(?,?,?,?,?)", book, student, user.id(), LocalDate.now().toString(), due.toString());
            audit("borrow docs #" + book + " etudiant #" + student);
            return null;
        });
    }

    void returnLoan(int id) throws SQLException {
        authenticated();
        transaction(() -> {
            if (execute("UPDATE emprunts SET emp_state=0 WHERE emp_id=? AND status=1 AND emp_state=1", id) != 1) throw new Rule("notFound");
            audit("return emprunts #" + id);
            return null;
        });
    }

    List<Row> auditLog(Integer staff) throws SQLException {
        admin();
        String sql = "SELECT t.*,s.cin FROM trace t LEFT JOIN staff s ON s.staff_id=t.staff_id";
        return staff == null ? query(sql + " ORDER BY t.trace DESC LIMIT 1000") : query(sql + " WHERE t.staff_id=? ORDER BY t.trace DESC LIMIT 1000", staff);
    }

    Map<String, Integer> statistics() throws SQLException {
        authenticated();
        return Map.of("books", scalar("SELECT count(*) FROM docs WHERE status=1"), "students", scalar("SELECT count(*) FROM etudiant WHERE status=1"),
                "loans", scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1"),
                "overdue", scalar("SELECT count(*) FROM emprunts WHERE status=1 AND emp_state=1 AND return_d<?", LocalDate.now().toString()));
    }

    List<Row> popularBooks() throws SQLException {
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
    private void authenticated() { if (user == null) throw new Rule("forbidden"); }
    private void admin() { authenticated(); if (!user.admin()) throw new Rule("forbidden"); }
    private void authorize(Entity entity) { authenticated(); if (entity == Entity.STAFF) admin(); }
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
    @Override public void close() throws SQLException { connection.close(); }
}
