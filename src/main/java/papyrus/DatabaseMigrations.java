package papyrus;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;

/** Additive migrations preserve legacy IDs and keep a database snapshot before upgrading. */
final class DatabaseMigrations {
    static final int VERSION = 11000;

    static void migrate(Connection connection, Path directory, boolean existing) throws SQLException, IOException {
        int version;
        try (Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery("PRAGMA user_version")) {
            version = result.getInt(1);
        }
        if (version > VERSION) throw new SQLException("Database was created by a newer Papyrus version");
        if (version == VERSION) return;
        if (existing) {
            Path backup = directory.resolve(version == 0 ? "test1.pre-1.0.0.db" : "test1.pre-1.1.0.db");
            if (!Files.exists(backup)) snapshot(connection, backup);
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            try {
                sql.execute("CREATE TABLE IF NOT EXISTS types(type_id INTEGER PRIMARY KEY AUTOINCREMENT,ty_libelle TEXT,status INTEGER DEFAULT 1)");
                sql.execute("CREATE TABLE IF NOT EXISTS categories(cat_id INTEGER PRIMARY KEY AUTOINCREMENT,cat_libelle TEXT,status INTEGER DEFAULT 1)");
                sql.execute("CREATE TABLE IF NOT EXISTS docs(doc_id INTEGER PRIMARY KEY AUTOINCREMENT,doc_libelle TEXT,status INTEGER DEFAULT 1,image TEXT,cat_id INTEGER,type_id INTEGER)");
                sql.execute("CREATE TABLE IF NOT EXISTS etudiant(etud_id INTEGER PRIMARY KEY AUTOINCREMENT,cne TEXT NOT NULL UNIQUE,nom TEXT,prenom TEXT,image TEXT,phone TEXT NOT NULL UNIQUE,email TEXT UNIQUE,status INTEGER DEFAULT 1)");
                sql.execute("CREATE TABLE IF NOT EXISTS staff(staff_id INTEGER PRIMARY KEY AUTOINCREMENT,cin TEXT NOT NULL UNIQUE,nom TEXT,prenom TEXT,phone TEXT,role INTEGER DEFAULT 2,pass TEXT NOT NULL,status INTEGER DEFAULT 1)");
                sql.execute("CREATE TABLE IF NOT EXISTS emprunts(emp_id INTEGER PRIMARY KEY AUTOINCREMENT,doc_id INTEGER,etud_id INTEGER,staff_id INTEGER,emp_state INTEGER DEFAULT 1,emp_d TEXT,return_d TEXT,status INTEGER DEFAULT 1)");
                sql.execute("CREATE TABLE IF NOT EXISTS trace(trace INTEGER PRIMARY KEY AUTOINCREMENT,date TEXT,info TEXT,staff_id INTEGER)");
                for (String table : new String[]{"docs", "etudiant", "staff", "types", "categories"}) addColumn(connection, table, "revision", "INTEGER NOT NULL DEFAULT 0");
                for (String field : new String[]{"author", "isbn", "shelf", "copy_code"}) addColumn(connection, "docs", field, "TEXT");
                addColumn(connection, "staff", "auth_version", "INTEGER NOT NULL DEFAULT 0");
                addColumn(connection, "emprunts", "returned_at", "TEXT");
                addColumn(connection, "emprunts", "returned_by", "INTEGER");
                addColumn(connection, "emprunts", "renewals", "INTEGER NOT NULL DEFAULT 0");
                sql.execute("CREATE TABLE IF NOT EXISTS app_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                sql.execute("INSERT OR IGNORE INTO app_meta VALUES('restore_epoch',lower(hex(randomblob(16))))");
                sql.execute("CREATE INDEX IF NOT EXISTS loan_availability ON emprunts(doc_id,status,emp_state)");
                sql.execute("CREATE INDEX IF NOT EXISTS loan_student ON emprunts(etud_id,status,emp_state)");
                sql.execute("CREATE INDEX IF NOT EXISTS loan_due ON emprunts(status,emp_state,return_d)");
                sql.execute("CREATE INDEX IF NOT EXISTS trace_staff ON trace(staff_id,trace)");
                sql.execute("CREATE INDEX IF NOT EXISTS document_category ON docs(cat_id,status)");
                sql.execute("CREATE INDEX IF NOT EXISTS document_type ON docs(type_id,status)");
                sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS document_copy_code ON docs(copy_code) WHERE copy_code IS NOT NULL AND copy_code<>''");
                sql.execute("PRAGMA user_version=" + VERSION);
                sql.execute("COMMIT");
            } catch (SQLException | RuntimeException ex) {
                sql.execute("ROLLBACK"); throw ex;
            }
        }
    }

    private static void addColumn(Connection connection, String table, String column, String declaration) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equals(result.getString("name"))) return;
        }
        try (Statement sql = connection.createStatement()) { sql.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + declaration); }
    }

    static void snapshot(Connection connection, Path target) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("VACUUM INTO ?")) {
            sql.setString(1, target.toAbsolutePath().toString()); sql.execute();
        }
    }
}
