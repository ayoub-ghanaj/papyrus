package papyrus;

/** Separate launcher allows jpackage to start JavaFX from the class path. */
public final class Launcher {
    private Launcher() {}
    public static void main(String[] args) {
        javafx.application.Application.launch(PapyrusApp.class, args);
    }
}
