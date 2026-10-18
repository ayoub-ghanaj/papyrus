package papyrus;

import java.util.Locale;
import java.util.ResourceBundle;

final class I18n {
    private ResourceBundle bundle;
    private String language;
    I18n(String language) { setLanguage(language); }
    void setLanguage(String language) {
        this.language = java.util.Set.of("fr", "en", "ar").contains(language) ? language : "fr";
        bundle = ResourceBundle.getBundle("papyrus.messages", Locale.forLanguageTag(this.language));
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(this.language));
    }
    String language() { return language; }
    boolean rtl() { return language.equals("ar"); }
    String text(String key) { return bundle.getString(key); }
}
