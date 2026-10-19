package papyrus;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Desktop integration exercise, launched separately from unit tests on a real display. */
public final class UiSmoke {
    private static final Queue<Runnable> STEPS = new ArrayDeque<>();
    private static PapyrusApp app;
    private static Stage stage;
    private static Dialog<ButtonType> dialog;
    private static Path data;
    private static final Path SCREENSHOTS = Path.of("target", "screenshots");

    public static void main(String[] args) throws Exception {
        UiTestSupport.start();
        data = Files.createTempDirectory("papyrus-ui-smoke-");
        System.setProperty("papyrus.dataDir", data.toString());
        Files.createDirectories(SCREENSHOTS);
        Platform.startup(() -> {
            try {
                app = new PapyrusApp(); stage = new Stage(); app.start(stage);
                steps(); advance();
            } catch (Throwable error) { fail(error); }
        });
    }

    private static void steps() {
        add(() -> screenshot("setup-fr-light"));
        add(() -> {
            var carousel = (LoginCarousel) stage.getScene().lookup(".login-carousel");
            check(carousel != null && carousel.slideCount() == 3, "Three local carousel images loaded");
            click("next-slide"); check(carousel.currentSlide() == 1, "Carousel navigation works");
        });
        add(() -> {
            text("username", "demo"); text("name", "Papyrus Demo"); text("password", "demo-password"); text("confirmPassword", "demo-password"); click("signIn");

        });
        await(() -> stage.getScene().lookup("#nav-dashboard") != null, "Setup opens workspace");
        add(() -> app.language("en"));
        if (!Boolean.getBoolean("papyrus.smoke.skipWindowManager")) {
        add(() -> stage.setMaximized(true));
        add(() -> {
            var bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
            check(Math.abs(stage.getWidth() - bounds.getWidth()) < 40, "Maximized window fills screen width");
            check(Math.abs(stage.getHeight() - bounds.getHeight()) < 40, "Maximized window fills screen height");
            stage.setFullScreen(true);
        });
        add(() -> {
            var bounds = javafx.stage.Screen.getPrimary().getBounds();
            check(stage.isFullScreen(), "Fullscreen activated");
            check(Math.abs(stage.getScene().getWidth() - bounds.getWidth()) < 3, "Fullscreen content fills display width");
            check(Math.abs(stage.getScene().getHeight() - bounds.getHeight()) < 3, "Fullscreen content fills display height");
            screenshot("fullscreen-en-light");
            stage.setFullScreen(false); stage.setMaximized(false); stage.setWidth(1240); stage.setHeight(860);
        });
        }
        add(() -> {
            try {
                app.store.save(Store.Entity.CATEGORIES, null, Map.of("cat_libelle", "Literature / أدب"));
                app.store.save(Store.Entity.TYPES, null, Map.of("ty_libelle", "Book / كتاب"));
                app.store.save(Store.Entity.STUDENTS, null, Map.of("cne", "ST-001", "nom", "O'Neil", "prenom", "أمين", "phone", "+212600000001"));
                app.navigate("books");
                dialog = app.editor(Store.Entity.BOOKS, null); dialog.show();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        await(() -> !combo("field-cat_id").getItems().isEmpty() && !combo("field-type_id").getItems().isEmpty(), "Classification selectors load");
        add(() -> {
            dialogText("doc_libelle", "The Library / المكتبة");
            combo("field-cat_id").getSelectionModel().selectFirst(); combo("field-type_id").getSelectionModel().selectFirst();
            dialogSave();
        });
        await(() -> !dialog.isShowing(), "Document form saved");
        add(() -> {
            try {
                check(app.store.list(Store.Entity.BOOKS).size() == 1, "UI saves document");
                check(!stage.getIcons().isEmpty(), "Window has an icon");
                Store.Row book = app.store.list(Store.Entity.BOOKS).getFirst();
                var values = new HashMap<>(book.values());
                values.put("image", AppPaths.importImage(Path.of("packaging", "papyrus.png"), app.store.directory));
                app.store.save(Store.Entity.BOOKS, book.number("doc_id"), values);
                app.navigate("books");
                stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                check(stage.getScene().lookup("#book-gallery") != null, "Cover gallery is visible");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        await(() -> stage.getScene().lookup("#book-gallery") instanceof BookGallery gallery && !gallery.getItems().isEmpty(), "Cover gallery loads records");
        add(() -> {
            try {
                screenshot("documents-en-light");
                dialog = app.newBorrowDialog(); dialog.show();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        await(() -> !combo("loan-book").getItems().isEmpty() && !combo("loan-student").getItems().isEmpty(), "Loan selectors load");
        add(() -> {
            var book = (SearchableComboBox<?>) combo("loan-book"); var student = (SearchableComboBox<?>) combo("loan-student");
            book.searchEditor().requestFocus(); book.show(); book.searchEditor().setText("Library");
            javafx.event.Event.fireEvent(book.searchEditor(), new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            check(book.getValue() != null, "Typing and Enter select a matching document");
            student.searchEditor().requestFocus(); student.show(); student.searchEditor().setText("600000001");
            javafx.event.Event.fireEvent(student.searchEditor(), new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
            check(student.getValue() != null, "Phone search selects a matching student");
            screenshot("loan-search-en", dialog.getDialogPane()); dialogSave();
        });
        await(() -> !dialog.isShowing(), "Loan form saved");
        add(() -> {
            try { check(app.store.statistics().get("loans") == 1, "UI creates loan"); } catch (Exception ex) { throw new RuntimeException(ex); }
            app.navigate("dashboard");
        });
        add(() -> screenshot("dashboard-en-light"));
        for (String language : List.of("fr", "en", "ar")) {
            add(() -> app.language(language));
            for (boolean dark : List.of(false, true)) {
                add(() -> app.theme(dark));
                for (String page : List.of("dashboard", "books", "students", "loans", "types", "categories", "staff", "activity", "settings")) {
                    add(() -> {
                        UiTestSupport.progress(language + " / " + dark + " / " + page);
                        app.navigate(page);
                        check(stage.getScene().getRoot().getNodeOrientation() == (language.equals("ar") ? javafx.geometry.NodeOrientation.RIGHT_TO_LEFT : javafx.geometry.NodeOrientation.LEFT_TO_RIGHT), "Screen direction");
                        if (language.equals("ar") && dark && List.of("dashboard", "loans", "settings").contains(page)) screenshot(page + "-ar-dark");
                    });
                }
            }
        }
        for (String language : List.of("en", "ar")) {
            add(() -> { app.language(language); app.theme(language.equals("ar")); });
            for (Store.Entity entity : Store.Entity.values()) {
                add(() -> { dialog = app.editor(entity, null); dialog.show(); });
                add(() -> {
                    screenshot("form-" + entity.name().toLowerCase(Locale.ROOT) + "-" + language, dialog.getDialogPane());
                    var window = (Stage) dialog.getDialogPane().getScene().getWindow();
                    check(!window.getIcons().isEmpty() && window.getIcons().getFirst().getWidth() == 128, "Dialog has a complete native icon asset");
                    check(window.getIcons().getFirst().getPixelReader().getColor(64, 64).getOpacity() > 0.9, "Dialog icon is opaque at its center");
                    if (entity == Store.Entity.STUDENTS && language.equals("en")) {
                        var image = new javafx.scene.robot.Robot().getScreenCapture(null, window.getX(), window.getY(), window.getWidth(), window.getHeight(), true);
                        saveImage("native-student-window-en", image);
                    }
                    dialog.close();
                });
            }
            add(() -> {
                try { dialog = app.newBorrowDialog(); dialog.show(); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            add(() -> { screenshot("form-loans-" + language, dialog.getDialogPane()); dialog.close(); });
        }
        add(() -> {
            try {
                int loan = app.store.loans(false, null, null).getFirst().number("emp_id");
                app.store.returnLoan(loan);
                check(app.store.loans(false, null, null).isEmpty(), "Loan returned");
                app.store.logout(); app.showLogin();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        add(() -> {
            text("username", "demo"); text("password", "demo-password"); click("signIn");
        });
        await(() -> stage.getScene().lookup("#nav-dashboard") != null, "Re-login succeeds");
        add(() -> {
            System.out.println("UI SMOKE PASSED: setup, login, create document, borrow, return, 54 page/theme/language combinations, screenshots");
        });
        add(() -> {
            try { app.stop(); stage.close(); Platform.exit(); }
            catch (Exception ex) { fail(ex); }
        });
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) {
        STEPS.add(new Runnable() {
            long deadline;
            @Override public void run() {
                if (deadline == 0) deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                UiTestSupport.step = message;
                if (condition.getAsBoolean()) { UiTestSupport.progress(message); return; }
                check(System.nanoTime() < deadline, message);
                ((ArrayDeque<Runnable>) STEPS).addFirst(this);
            }
        });
    }
    private static void add(Runnable runnable) { STEPS.add(runnable); }
    private static void advance() {
        Runnable next = STEPS.poll(); if (next == null) return;
        try {
            next.run();
            UiTestSupport.next(UiSmoke::advance, 180);
        } catch (Throwable error) { fail(error); }
    }
    private static void text(String id, String value) { ((TextInputControl) stage.getScene().lookup("#" + id)).setText(value); }
    private static void dialogText(String id, String value) { ((TextInputControl) dialog.getDialogPane().lookup("#field-" + id)).setText(value); }
    private static ComboBox<?> combo(String id) { return (ComboBox<?>) dialog.getDialogPane().lookup("#" + id); }
    private static void click(String id) { ((Button) stage.getScene().lookup("#" + id)).fire(); }
    private static void dialogSave() {
        ((Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst())).fire();

    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void screenshot(String name) {
        screenshot(name, stage.getScene().getRoot());
    }
    static void screenshot(String name, Node root) {
        try {
            UiTestSupport.progress("screenshot " + name);
            root.applyCss(); ((javafx.scene.Parent) root).layout();
            WritableImage image = root.snapshot(null, null);
            saveImage(name, image);
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private static void saveImage(String name, WritableImage image) {
        try {
            Files.createDirectories(SCREENSHOTS);
            BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            ImageIO.write(png, "png", SCREENSHOTS.resolve(name + ".png").toFile());
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private static void fail(Throwable error) { error.printStackTrace(); System.exit(1); }
}
