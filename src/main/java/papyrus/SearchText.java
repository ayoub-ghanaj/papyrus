package papyrus;

import java.text.Normalizer;
import java.util.*;

final class SearchText {
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("ـ", "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي');
    }
    static boolean matches(Object item, String query) {
        String searchable = item instanceof Store.Row row ? String.join(" ", row.values().entrySet().stream()
                .filter(entry -> !Set.of("image", "pass").contains(entry.getKey())).map(entry -> Objects.toString(entry.getValue(), "")).toList()) : Objects.toString(item, "");
        String value = normalize(searchable);
        return Arrays.stream(normalize(query).strip().split("\\s+")).allMatch(value::contains);
    }
}
