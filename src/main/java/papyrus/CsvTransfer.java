package papyrus;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** UTF-8 CSV with fixed portable headers, quoted fields, and spreadsheet formula protection. */
final class CsvTransfer {
    static List<String> headers(Store.Entity entity) {
        return switch (entity) {
            case BOOKS -> List.of("title", "author", "isbn", "shelf", "copy_code", "category", "type");
            case STUDENTS -> List.of("student_id", "first_name", "last_name", "phone", "email");
            default -> throw new IllegalArgumentException("Unsupported CSV entity");
        };
    }
    static List<Map<String, String>> read(Path file, Store.Entity entity) throws IOException {
        if (Files.size(file) > 10 * 1024 * 1024) throw new IOException("CSV exceeds 10 MB");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = parse(text);
        if (rows.isEmpty() || !rows.getFirst().equals(headers(entity))) throw new IOException("Expected headers: " + String.join(",", headers(entity)));
        if (rows.size() > 5001) throw new IOException("Import at most 5000 records at a time");
        List<Map<String, String>> result = new ArrayList<>();
        List<String> fields = fields(entity);
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() == 1 && row.getFirst().isBlank()) continue;
            if (row.size() != fields.size()) throw new IOException("Invalid column count on record " + (index + 1));
            Map<String, String> record = new LinkedHashMap<>();
            for (int column = 0; column < fields.size(); column++) {
                String value = row.get(column);
                if (value.length() > 4096) throw new IOException("Field exceeds 4096 characters on record " + (index + 1));
                record.put(fields.get(column), unprotect(value));
            }
            result.add(Collections.unmodifiableMap(record));
        }
        return List.copyOf(result);
    }
    static void export(Path file, Store.Entity entity, List<Store.Row> records) throws IOException {
        List<List<String>> rows = new ArrayList<>(); rows.add(headers(entity));
        for (Store.Row record : records) rows.add(fields(entity).stream().map(field -> record.text(switch (field) { case "category" -> "cat_libelle"; case "type" -> "ty_libelle"; default -> field; })).toList());
        write(file, rows);
    }
    static void exportLoans(Path file, List<Store.Row> records) throws IOException {
        List<String> fields = List.of("emp_id", "doc_libelle", "copy_code", "cne", "student", "phone", "emp_d", "return_d", "returned_at", "renewals", "emp_state");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("loan_id", "title", "copy_code", "student_id", "student", "phone", "borrowed_on", "due_date", "returned_at", "renewals", "active"));
        for (Store.Row record : records) rows.add(fields.stream().map(record::text).toList());
        write(file, rows);
    }
    static void template(Path file, Store.Entity entity) throws IOException { write(file, List.of(headers(entity))); }

    private static List<String> fields(Store.Entity entity) {
        return entity == Store.Entity.BOOKS ? List.of("doc_libelle", "author", "isbn", "shelf", "copy_code", "category", "type") : List.of("cne", "prenom", "nom", "phone", "email");
    }
    static List<List<String>> parse(String text) throws IOException {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder(); boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else field.append(c);
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closed = false;
                if (c != ',') {
                    rows.add(List.copyOf(row)); row.clear();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else if (closed) throw new IOException("Unexpected text after a quoted field");
            else if (c == '"') {
                if (!field.isEmpty()) throw new IOException("Quote inside an unquoted field");
                quoted = true;
            } else field.append(c);
        }
        if (quoted) throw new IOException("Unclosed quoted field");
        if (!row.isEmpty() || !field.isEmpty() || closed) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
    private static void write(Path file, List<List<String>> rows) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".papyrus-csv-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                for (List<String> row : rows) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) writer.write(',');
                        writer.write('"'); writer.write(protect(row.get(i)).replace("\"", "\"\"")); writer.write('"');
                    }
                    writer.write("\r\n");
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static boolean formula(String value) { return !value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0; }
    private static String protect(String value) { return formula(value) || value.startsWith("'") ? "'" + value : value; }
    private static String unprotect(String value) { return value.startsWith("'") && (formula(value.substring(1)) || value.startsWith("''")) ? value.substring(1) : value; }
}
