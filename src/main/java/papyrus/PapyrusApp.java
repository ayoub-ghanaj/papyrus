package papyrus;

import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.*;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

public final class PapyrusApp extends Application {
    private final Properties preferences = new Properties();
    private I18n i18n;
    private boolean dark;
    private Stage stage;
    private BorderPane root;
    private VBox content;
    private String page = "dashboard";
    private String searchValue = "";
    private final Map<String, Image> windowIcons = new HashMap<>();
    private LoginCarousel carousel;
    Store store;

    @Override public void start(Stage stage) {
        this.stage = stage;
        i18n = new I18n("fr");
        try {
            store = new Store(AppPaths.dataDirectory());
            Path file = store.directory.resolve("settings.properties");
            if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { preferences.load(input); }
            i18n.setLanguage(preferences.getProperty("language", "fr"));
            dark = preferences.getProperty("theme", "light").equals("dark");
            stage.setTitle("Papyrus");
            stage.getIcons().setAll(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/papyrus/icon.png"))));
            stage.setResizable(true);
            stage.setFullScreenExitHint("");
            stage.setMinWidth(960); stage.setMinHeight(680);
            showLogin();
            stage.show();
        } catch (Exception ex) {
            ex.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR, t("startupError"), new ButtonType(t("close"), ButtonBar.ButtonData.OK_DONE));
            alert.showAndWait();
            javafx.application.Platform.exit();
        }
    }

    @Override public void stop() throws Exception { stopCarousel(); if (store != null) store.close(); }
    private void stopCarousel() { if (carousel != null) { carousel.dispose(); carousel = null; } }
    String t(String key) { return i18n.text(key); }

    void language(String value) {
        i18n.setLanguage(value); preferences.setProperty("language", value); persistPreferences(); rebuild();
    }
    void theme(boolean value) {
        dark = value; preferences.setProperty("theme", value ? "dark" : "light"); persistPreferences(); rebuild();
    }
    private void persistPreferences() {
        try (var output = Files.newOutputStream(store.directory.resolve("settings.properties"))) {
            preferences.store(output, "Papyrus preferences");
        } catch (IOException ex) { showError(ex); }
    }
    private void rebuild() { if (store.user() == null) showLogin(); else showWorkspace(); }
    private void apply(Parent parent) {
        parent.getStylesheets().setAll(Objects.requireNonNull(getClass().getResource("/papyrus/app.css")).toExternalForm());
        parent.getStyleClass().removeAll("light", "dark");
        parent.getStyleClass().add(dark ? "dark" : "light");
        parent.setNodeOrientation(i18n.rtl() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
    }
    private void setRoot(BorderPane pane) {
        root = pane; apply(root);
        if (stage.getScene() == null) {
            stage.setScene(new Scene(root, 1240, 820));
            stage.getScene().getAccelerators().put(new KeyCodeCombination(KeyCode.F11), () -> stage.setFullScreen(!stage.isFullScreen()));
        }
        else stage.getScene().setRoot(root);
    }

    private HBox preferencesBar() {
        ComboBox<String> language = new ComboBox<>(FXCollections.observableArrayList("Français", "English", "العربية"));
        language.setId("language"); language.setAccessibleText(t("language"));
        language.setValue(switch (i18n.language()) { case "en" -> "English"; case "ar" -> "العربية"; default -> "Français"; });
        language.setOnAction(event -> language(switch (language.getValue()) { case "English" -> "en"; case "العربية" -> "ar"; default -> "fr"; }));
        Button appearance = button(dark ? "light" : "dark", () -> theme(!dark));
        appearance.setId("theme"); appearance.setGraphic(icon(dark ? "sun" : "moon"));
        HBox box = new HBox(10, language, appearance); box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    void showLogin() {
        try {
            stopCarousel();
            stage.setTitle("Papyrus");
            stage.getIcons().setAll(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/papyrus/icon.png"))));
            boolean setup = store.needsSetup();
            BorderPane pane = new BorderPane();
            HBox top = new HBox(brand(), spacer(), preferencesBar()); top.setAlignment(Pos.CENTER_LEFT); top.getStyleClass().add("login-top");
            pane.setTop(top);

            Label eyebrow = label("PAPYRUS / " + t("library"), "eyebrow");
            Label title = label(t(setup ? "setup" : "welcome"), "login-title"); title.setWrapText(true);
            Label intro = label(t(setup ? "setupIntro" : "loginIntro"), "muted"); intro.setWrapText(true);
            TextField username = new TextField(); username.setId("username"); username.setPromptText(t("login"));
            TextField name = new TextField(); name.setId("name");
            PasswordField password = new PasswordField(); password.setId("password");
            PasswordField confirm = new PasswordField(); confirm.setId("confirmPassword");
            Label error = label("", "error-label"); error.setWrapText(true); error.setVisible(false); error.setManaged(false);
            Button submit = button(setup ? "createAccount" : "signIn", () -> {
                try {
                    if (setup) {
                        if (!password.getText().equals(confirm.getText())) throw new Store.Rule("passwordMismatch");
                        store.setup(username.getText(), name.getText(), password.getText());
                    }
                    store.login(username.getText(), password.getText());
                    password.clear(); confirm.clear(); page = "dashboard"; showWorkspace();
                } catch (Exception ex) {
                    error.setText(message(ex)); error.setVisible(true); error.setManaged(true);
                }
            });
            submit.setId("signIn"); submit.getStyleClass().add("primary"); submit.setMaxWidth(Double.MAX_VALUE); submit.setDefaultButton(true);
            VBox form = new VBox(12, eyebrow, title, intro, new Region(), field("login", username));
            if (setup) form.getChildren().add(field("name", name));
            form.getChildren().add(field("password", password));
            if (setup) form.getChildren().add(field("confirmPassword", confirm));
            form.getChildren().addAll(error, submit);
            form.setMaxWidth(390); form.setAlignment(Pos.CENTER_LEFT);

            carousel = new LoginCarousel(i18n);
            LoginCarousel illustration = carousel;
            HBox center = new HBox(80, form, illustration); center.setAlignment(Pos.CENTER); center.setPadding(new Insets(20, 45, 35, 45));
            HBox.setHgrow(form, Priority.ALWAYS); HBox.setHgrow(illustration, Priority.ALWAYS);
            pane.setCenter(center);
            Label note = label(t("setupNote"), "muted"); note.setWrapText(true);
            BorderPane.setMargin(note, new Insets(10, 40, 24, 40)); pane.setBottom(note);
            setRoot(pane);
            username.requestFocus();
        } catch (SQLException ex) { showError(ex); }
    }

    void showWorkspace() {
        stopCarousel();
        stage.setTitle("Papyrus · " + t(page));
        stage.getIcons().setAll(windowIcon(page));
        BorderPane pane = new BorderPane();
        VBox sidebar = new VBox(8); sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(218); sidebar.setMinWidth(218);
        Label section = label(t("library"), "eyebrow"); section.setWrapText(true); VBox.setMargin(section, new Insets(24, 0, 10, 10));
        sidebar.getChildren().addAll(brand(), section);
        for (String key : List.of("dashboard", "books", "students", "loans", "types", "categories", "staff", "activity")) {
            if ((key.equals("staff") || key.equals("activity")) && !store.user().admin()) continue;
            Button nav = button(key, () -> navigate(key)); nav.setGraphic(icon(key)); nav.setId("nav-" + key);
            nav.getStyleClass().add("nav"); if (page.equals(key)) nav.getStyleClass().add("selected");
            nav.setMaxWidth(Double.MAX_VALUE); nav.setAlignment(Pos.CENTER_LEFT); sidebar.getChildren().add(nav);
        }
        sidebar.getChildren().add(spacer());
        Button settings = button("settings", () -> navigate("settings")); settings.setGraphic(icon("settings")); settings.getStyleClass().add("nav"); settings.setId("nav-settings"); settings.setMaxWidth(Double.MAX_VALUE);
        if (page.equals("settings")) settings.getStyleClass().add("selected");
        Label user = label(store.user().name(), "account-name"); user.setWrapText(true);
        Label role = label(t(store.user().admin() ? "admin" : "staffRole"), "muted");
        Button logout = button("logout", () -> { try { store.logout(); showLogin(); } catch (SQLException ex) { showError(ex); } });
        logout.getStyleClass().add("quiet"); logout.setId("logout");
        sidebar.getChildren().addAll(settings, new Separator(), user, role, logout);
        pane.setLeft(sidebar);

        VBox workspace = new VBox();
        HBox top = new HBox(label(t("localFirst"), "muted"), spacer(), preferencesBar()); top.getStyleClass().add("topbar"); top.setAlignment(Pos.CENTER_LEFT);
        content = new VBox(22); content.getStyleClass().add("content"); content.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.setFitToHeight(true); scroll.getStyleClass().add("workspace-scroll");
        workspace.getChildren().addAll(top, scroll); VBox.setVgrow(scroll, Priority.ALWAYS);
        pane.setCenter(workspace); setRoot(pane);
        try { renderPage(); } catch (Exception ex) { showError(ex); }
    }

    void navigate(String page) { this.page = page; searchValue = ""; showWorkspace(); }

    private void renderPage() throws SQLException {
        content.getChildren().clear();
        switch (page) {
            case "dashboard" -> dashboard();
            case "books" -> booksPage();
            case "students" -> entityPage(Store.Entity.STUDENTS, "studentsHint");
            case "staff" -> entityPage(Store.Entity.STAFF, "staffHint");
            case "types" -> entityPage(Store.Entity.TYPES, "typesHint");
            case "categories" -> entityPage(Store.Entity.CATEGORIES, "categoriesHint");
            case "loans" -> loanPage();
            case "activity" -> activityPage();
            case "settings" -> settingsPage();
            default -> throw new IllegalStateException("Unknown page: " + page);
        }
    }

    private void dashboard() throws SQLException {
        HBox heading = heading("dashboard", "dashboardHint");
        heading.getChildren().add(button("borrow", this::borrowDialog)); content.getChildren().add(heading);
        Map<String, Integer> totals = store.statistics();
        GridPane cards = new GridPane(); cards.setHgap(14);
        String[] labels = {"totalDocuments", "totalStudents", "activeLoansLabel", "overdue"};
        String[] keys = {"books", "students", "loans", "overdue"};
        for (int i = 0; i < keys.length; i++) {
            VBox card = new VBox(14, label(t(labels[i]), "muted"), label(Integer.toString(totals.get(keys[i])), "stat-value"));
            card.getStyleClass().add("card"); card.setMinWidth(0);
            if (i == 3) card.getStyleClass().add("overdue-card");
            GridPane.setHgrow(card, Priority.ALWAYS); cards.add(card, i, 0);
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(25); cards.getColumnConstraints().add(column);
        }
        content.getChildren().add(cards);
        CategoryAxis names = new CategoryAxis(); NumberAxis counts = new NumberAxis(); counts.setTickUnit(1); counts.setMinorTickVisible(false);
        BarChart<String, Number> chart = new BarChart<>(names, counts); chart.setLegendVisible(false); chart.setAnimated(false); chart.setPrefHeight(230);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Store.Row row : store.popularBooks()) series.getData().add(new XYChart.Data<>(row.text("doc_libelle"), row.number("total")));
        chart.getData().add(series);
        VBox chartCard = new VBox(12, label(t("popularBooks"), "section-title"), series.getData().isEmpty() ? empty("noActivity") : chart);
        chartCard.getStyleClass().add("card"); content.getChildren().add(chartCard);
        TableView<Store.Row> recent = loanTable(store.loans(true, null, null).stream().limit(8).toList()); recent.setPrefHeight(260);
        content.getChildren().addAll(label(t("recentLoans"), "section-title"), recent);
    }

    private void entityPage(Store.Entity entity, String hint) throws SQLException {
        HBox heading = heading(page, hint);
        Button add = button("add", () -> editor(entity, null).showAndWait()); add.setId("add"); add.getStyleClass().add("primary");
        heading.getChildren().add(add); content.getChildren().add(heading);
        List<Store.Row> rows = store.list(entity);
        TableView<Store.Row> table = table(); table.setId("records");
        switch (entity) {
            case BOOKS -> { column(table, "doc_libelle", "doc_libelle", 260); column(table, "cat_libelle", "cat_libelle", 130); column(table, "ty_libelle", "ty_libelle", 120); column(table, "state", row -> t(row.text("borrowed").equals("1") ? "borrowed" : "available"), 130); }
            case STUDENTS -> { column(table, "cne", "cne", 120); column(table, "prenom", "prenom", 150); column(table, "nom", "nom", 150); column(table, "phone", "phone", 140); column(table, "email", "email", 190); }
            case STAFF -> { column(table, "cin", "cin", 180); column(table, "prenom", "prenom", 140); column(table, "nom", "nom", 140); column(table, "phone", "phone", 130); column(table, "role", row -> t(row.text("role").equals("1") ? "admin" : "staffRole"), 150); }
            case TYPES -> column(table, "ty_libelle", "ty_libelle", 500);
            case CATEGORIES -> column(table, "cat_libelle", "cat_libelle", 500);
        }
        TextField search = searchField();
        ComboBox<String> availability = new ComboBox<>(FXCollections.observableArrayList(t("all"), t("available"), t("borrowed")));
        availability.setValue(t("all")); availability.setAccessibleText(t("filterAvailability"));
        ComboBox<Store.Row> category = new SearchableComboBox<>(store.choices(Store.Entity.CATEGORIES), i18n);
        ComboBox<Store.Row> type = new SearchableComboBox<>(store.choices(Store.Entity.TYPES), i18n);
        category.setPromptText(t("cat_libelle")); type.setPromptText(t("ty_libelle")); category.setAccessibleText(t("cat_libelle")); type.setAccessibleText(t("ty_libelle"));
        FilteredList<Store.Row> filtered = new FilteredList<>(FXCollections.observableArrayList(rows));
        ComboBox<String> roles = new ComboBox<>(FXCollections.observableArrayList(t("all"), t("admin"), t("staffRole"))); roles.setValue(t("all")); roles.setAccessibleText(t("role"));
        Runnable filter = () -> {
            searchValue = search.getText(); String query = searchValue.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(row -> SearchText.matches(row, query)
                    && (entity != Store.Entity.STAFF || roles.getSelectionModel().getSelectedIndex() == 0 || row.text("role").equals(Integer.toString(roles.getSelectionModel().getSelectedIndex())))
                    && (entity != Store.Entity.BOOKS || ((availability.getSelectionModel().getSelectedIndex() == 0 || row.text("borrowed").equals(availability.getSelectionModel().getSelectedIndex() == 1 ? "0" : "1"))
                    && (category.getValue() == null || category.getValue().text("cat_id").equals(row.text("cat_id")))
                    && (type.getValue() == null || type.getValue().text("type_id").equals(row.text("type_id"))))));
            table.setPlaceholder(empty(query.isEmpty() ? "emptyHint" : "noResults"));
        };
        search.textProperty().addListener((o, a, b) -> filter.run());
        availability.setOnAction(e -> filter.run()); category.setOnAction(e -> filter.run()); type.setOnAction(e -> filter.run());
        roles.setOnAction(e -> filter.run());
        var sorted = new javafx.collections.transformation.SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted); filter.run();
        HBox tools = new HBox(10, search); tools.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(search, Priority.ALWAYS);
        if (entity == Store.Entity.BOOKS) tools.getChildren().addAll(availability, category, type);
        if (entity == Store.Entity.STAFF) tools.getChildren().add(roles);
        tools.getChildren().add(button("refresh", this::refresh));

        Button edit = button("edit", () -> selected(table, row -> editor(entity, row).showAndWait())); edit.setId("edit");
        Button delete = button("delete", () -> selected(table, row -> { if (confirm("deleteConfirm")) run(() -> { store.delete(entity, row.number(entity.id)); refresh(); }); })); delete.getStyleClass().add("danger"); delete.setId("delete");
        HBox actions = new HBox(10, edit, delete);
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()); delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        if (List.of(Store.Entity.BOOKS, Store.Entity.STUDENTS, Store.Entity.STAFF).contains(entity)) {
            Button history = button("history", () -> selected(table, row -> historyDialog(entity, row))); history.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()); actions.getChildren().add(history);
        }
        Label count = label(rows.size() + " " + t("recordCount"), "muted"); actions.getChildren().addAll(spacer(), count); actions.setAlignment(Pos.CENTER_LEFT);
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) editor(entity, table.getSelectionModel().getSelectedItem()).showAndWait(); });
        content.getChildren().addAll(tools, table, actions);
    }

    private void booksPage() throws SQLException {
        HBox heading = heading("books", "documentsHint");
        Button add = button("add", () -> editor(Store.Entity.BOOKS, null).showAndWait()); add.setId("add"); add.getStyleClass().add("primary"); heading.getChildren().add(add);
        List<Store.Row> rows = store.list(Store.Entity.BOOKS);
        TextField search = searchField();
        ComboBox<String> availability = new ComboBox<>(FXCollections.observableArrayList(t("all"), t("available"), t("borrowed")));
        availability.setValue(t("all")); availability.setAccessibleText(t("filterAvailability"));
        ComboBox<Store.Row> category = new SearchableComboBox<>(store.choices(Store.Entity.CATEGORIES), i18n);
        ComboBox<Store.Row> type = new SearchableComboBox<>(store.choices(Store.Entity.TYPES), i18n);
        category.setPromptText(t("cat_libelle")); type.setPromptText(t("ty_libelle"));
        category.setAccessibleText(t("cat_libelle")); type.setAccessibleText(t("ty_libelle"));
        HBox tools = new HBox(10, search, availability, category, type, button("refresh", this::refresh)); tools.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(search, Priority.ALWAYS);
        javafx.beans.property.ObjectProperty<Store.Row> selected = new javafx.beans.property.SimpleObjectProperty<>();
        TilePane gallery = new TilePane(18, 18); gallery.setPrefTileWidth(210); gallery.setPrefTileHeight(365); gallery.setAlignment(Pos.TOP_LEFT); gallery.setPadding(new Insets(2)); gallery.setId("book-gallery");
        selected.addListener((observable, before, after) -> gallery.getChildren().forEach(card -> card.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), Objects.equals(card.getProperties().get("record"), after))));
        ScrollPane scroll = new ScrollPane(gallery); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(490); VBox.setVgrow(scroll, Priority.ALWAYS);
        Label count = label("", "muted");
        Runnable filter = () -> {
            searchValue = search.getText(); String query = searchValue.strip().toLowerCase(Locale.ROOT);
            List<Store.Row> visible = rows.stream().filter(row -> SearchText.matches(row, query)
                    && (availability.getSelectionModel().getSelectedIndex() == 0 || row.text("borrowed").equals(availability.getSelectionModel().getSelectedIndex() == 1 ? "0" : "1"))
                    && (category.getValue() == null || category.getValue().text("cat_id").equals(row.text("cat_id")))
                    && (type.getValue() == null || type.getValue().text("type_id").equals(row.text("type_id")))).toList();
            selected.set(null); gallery.getChildren().clear();
            for (Store.Row row : visible) {
                ImageView image = new ImageView(); image.setFitWidth(174); image.setFitHeight(190); image.setPreserveRatio(true); showImage(image, row.text("image"));
                VBox placeholder = new VBox(10, icon("books"), label(t("noImage"), "muted")); placeholder.setAlignment(Pos.CENTER); placeholder.visibleProperty().bind(image.imageProperty().isNull());
                StackPane cover = new StackPane(placeholder, image); cover.getStyleClass().add("book-cover"); cover.setPrefHeight(200); cover.setMinHeight(200);
                Label title = label(row.text("doc_libelle"), "book-title"); title.setWrapText(true); title.setMaxWidth(180); title.setMinHeight(40);
                Label classification = label(row.text("cat_libelle") + " · " + row.text("ty_libelle"), "muted"); classification.setMaxWidth(180);
                Label state = label(t(row.text("borrowed").equals("1") ? "borrowed" : "available"), "book-status");
                Button borrow = button("borrow", () -> run(() -> newBorrowDialog(row.number("doc_id")).showAndWait()));
                borrow.getStyleClass().add("quiet"); borrow.setDisable(row.text("borrowed").equals("1")); borrow.setMaxWidth(Double.MAX_VALUE);
                VBox card = new VBox(7, cover, title, classification, state, borrow); card.setPrefWidth(210); card.getStyleClass().add("book-card");
                card.setFocusTraversable(true); card.setAccessibleText(row.text("doc_libelle"));
                card.setOnMouseClicked(event -> { selected.set(row); if (event.getClickCount() == 2) editor(Store.Entity.BOOKS, row).showAndWait(); });
                card.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) selected.set(row); });
                card.getProperties().put("record", row);
                gallery.getChildren().add(card);
            }
            scroll.setContent(visible.isEmpty() ? empty(query.isEmpty() ? "emptyHint" : "noResults") : gallery);
            count.setText(visible.size() + " " + t("recordCount"));
        };
        search.textProperty().addListener((o, before, after) -> filter.run()); availability.setOnAction(e -> filter.run()); category.setOnAction(e -> filter.run()); type.setOnAction(e -> filter.run());
        Button edit = button("edit", () -> editor(Store.Entity.BOOKS, selected.get()).showAndWait()); edit.setId("edit"); edit.disableProperty().bind(selected.isNull());
        Button delete = button("delete", () -> { if (confirm("deleteConfirm")) run(() -> { store.delete(Store.Entity.BOOKS, selected.get().number("doc_id")); refresh(); }); }); delete.getStyleClass().add("danger"); delete.disableProperty().bind(selected.isNull());
        Button history = button("history", () -> historyDialog(Store.Entity.BOOKS, selected.get())); history.disableProperty().bind(selected.isNull());
        HBox actions = new HBox(10, edit, delete, history, spacer(), count); actions.setAlignment(Pos.CENTER_LEFT);
        filter.run(); content.getChildren().addAll(heading, tools, scroll, actions);
    }

    private String searchable(Store.Entity entity, Store.Row row) {
        // Excludes credentials and internal image file names.
        return String.join(" ", switch (entity) {
            case BOOKS -> List.of(row.text("doc_libelle"), row.text("cat_libelle"), row.text("ty_libelle"));
            case STUDENTS -> List.of(row.text("cne"), row.text("nom"), row.text("prenom"), row.text("phone"), row.text("email"));
            case STAFF -> List.of(row.text("cin"), row.text("nom"), row.text("prenom"), row.text("phone"));
            case TYPES -> List.of(row.text("ty_libelle")); case CATEGORIES -> List.of(row.text("cat_libelle"));
        });
    }

    Dialog<ButtonType> editor(Store.Entity entity, Store.Row row) {
        Dialog<ButtonType> dialog = dialog(t(row == null ? "add" : "edit") + " · " + t(entity.name().toLowerCase(Locale.ROOT)), true, entity.name().toLowerCase(Locale.ROOT));
        VBox fields = new VBox(22); fields.setPadding(new Insets(8)); fields.getStyleClass().add("editor");
        String formTitle = t(row == null ? "add" : "edit") + " · " + t(entity.name().toLowerCase(Locale.ROOT));
        Label formHint = label(t("formHint"), "muted"); formHint.setWrapText(true);
        HBox introduction = new HBox(14, icon(entity.name().toLowerCase(Locale.ROOT)), new VBox(7, label(formTitle, "section-title"), formHint));
        introduction.setAlignment(Pos.CENTER_LEFT); introduction.getStyleClass().add("form-intro");
        GridPane grid = new GridPane(); grid.setHgap(16); grid.setVgap(20);
        ColumnConstraints first = new ColumnConstraints(); first.setPercentWidth(50); first.setHgrow(Priority.ALWAYS);
        ColumnConstraints second = new ColumnConstraints(); second.setPercentWidth(50); second.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(first, second);
        VBox information = new VBox(16, label(t(entity == Store.Entity.STAFF ? "accountDetails" : "recordDetails"), "form-section"), grid);
        HBox body = new HBox(26, information); HBox.setHgrow(information, Priority.ALWAYS); information.setMinWidth(360);
        fields.getChildren().addAll(introduction, body);
        int position = 0;
        Set<String> required = switch (entity) {
            case BOOKS -> Set.of("doc_libelle", "cat_id", "type_id");
            case STUDENTS -> Set.of("cne", "nom", "prenom", "phone");
            case STAFF -> row == null ? Set.of("cin", "nom", "prenom", "role", "pass") : Set.of("cin", "nom", "prenom", "role");
            case TYPES -> Set.of("ty_libelle"); case CATEGORIES -> Set.of("cat_libelle");
        };
        Map<String, Control> controls = new LinkedHashMap<>();
        ImageView preview = new ImageView(); preview.setFitWidth(155); preview.setFitHeight(155); preview.setPreserveRatio(true);
        String[] imagePath = {row == null ? "" : row.text("image")};
        Path[] pendingImage = {null};
        showImage(preview, imagePath[0]);
        for (String key : entity.fields) {
            if (key.equals("image")) {
                Button choose = button("chooseImage", () -> {
                    FileChooser chooser = new FileChooser(); chooser.setTitle(t("chooseImage"));
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(t("image"), "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
                    File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
                    if (file != null) { pendingImage[0] = file.toPath(); showImage(preview, file.toURI().toString()); }
                });
                Button remove = button("removeImage", () -> { pendingImage[0] = null; imagePath[0] = ""; preview.setImage(null); });
                choose.setMaxWidth(Double.MAX_VALUE); remove.setMaxWidth(Double.MAX_VALUE); remove.getStyleClass().add("quiet");
                VBox placeholder = new VBox(10, icon(entity == Store.Entity.STUDENTS ? "students" : "books"), label(t("noImage"), "muted")); placeholder.setAlignment(Pos.CENTER);
                placeholder.visibleProperty().bind(preview.imageProperty().isNull());
                StackPane frame = new StackPane(placeholder, preview); frame.getStyleClass().add("image-frame"); frame.setPrefSize(190, 180); frame.setMinHeight(180); frame.setMaxHeight(180);
                Label imageHint = label(t("imageHint"), "muted"); imageHint.setWrapText(true); imageHint.setMaxWidth(190);
                VBox imagePanel = new VBox(10, label(t("image"), "form-section"), frame, choose, remove, imageHint); imagePanel.setMinWidth(190); imagePanel.setMaxWidth(190);
                body.getChildren().add(imagePanel);
                continue;
            }
            Control control;
            if (key.equals("cat_id") || key.equals("type_id")) {
                Store.Entity choiceEntity = key.equals("cat_id") ? Store.Entity.CATEGORIES : Store.Entity.TYPES;
                ComboBox<Store.Row> combo = new SearchableComboBox<>(List.of(), i18n); combo.setMaxWidth(Double.MAX_VALUE);
                run(() -> combo.getItems().setAll(store.choices(choiceEntity)));
                if (row != null) combo.getItems().stream().filter(item -> item.text(choiceEntity.id).equals(row.text(key))).findFirst().ifPresent(combo::setValue);
                control = combo;
            } else if (key.equals("role")) {
                ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(t("admin"), t("staffRole"))); combo.setMaxWidth(Double.MAX_VALUE);
                combo.getSelectionModel().select(row != null && row.text(key).equals("1") ? 0 : 1); control = combo;
            } else {
                TextField text = key.equals("pass") ? new PasswordField() : new TextField();
                if (row != null && !key.equals("pass")) text.setText(row.text(key));
                if (key.equals("pass") && row != null) text.setPromptText(t("passwordHint"));
                control = text;
            }
            control.setId("field-" + key); controls.put(key, control);
            control.setMaxWidth(Double.MAX_VALUE);
            String labelKey = key.equals("cat_id") ? "cat_libelle" : key.equals("type_id") ? "ty_libelle" : key;
            VBox field = formField(labelKey, control, required.contains(key));
            boolean wide = Set.of("doc_libelle", "ty_libelle", "cat_libelle", "pass").contains(key);
            if (wide && position % 2 != 0) position++;
            grid.add(field, position % 2, position / 2, wide ? 2 : 1, 1);
            position += wide ? 2 : 1;
        }
        Label requiredHint = label(t("requiredHint"), "muted");
        Label error = label("", "error-label"); error.setWrapText(true); fields.getChildren().addAll(requiredHint, error);
        ScrollPane scroll = new ScrollPane(fields); scroll.setFitToWidth(true);
        double desiredHeight = entity.fields.contains("image") ? 570 : entity.fields.size() > 4 ? 470 : 240;
        double availableHeight = Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).getFirst().getVisualBounds().getHeight() - 180;
        scroll.setPrefViewportHeight(Math.min(desiredHeight, Math.max(300, availableHeight)));
        scroll.setPrefViewportWidth(entity.fields.contains("image") ? 780 : 600);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getStyleClass().add("editor-dialog");
        Button save = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Path imported = null;
            try {
                Map<String, String> values = new LinkedHashMap<>();
                boolean missing = false;
                for (var entry : controls.entrySet()) {
                    Control control = entry.getValue(); String value;
                    if (control instanceof TextInputControl text) value = text.getText();
                    else if (entry.getKey().equals("role")) value = ((ComboBox<?>) control).getSelectionModel().getSelectedIndex() == 0 ? "1" : "2";
                    else { Store.Row choice = (Store.Row) ((ComboBox<?>) control).getValue(); value = choice == null ? "" : choice.text(entry.getKey()); }
                    values.put(entry.getKey(), value);
                    boolean invalid = required.contains(entry.getKey()) && value.isBlank();
                    control.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), invalid);
                    missing |= invalid;
                }
                if (missing) throw new Store.Rule("required");
                if (pendingImage[0] != null) {
                    Image image = new Image(pendingImage[0].toUri().toString());
                    if (image.isError()) throw new Store.Rule("imageError");
                    try { imagePath[0] = AppPaths.importImage(pendingImage[0], store.directory); imported = Path.of(java.net.URI.create(imagePath[0])); }
                    catch (IOException ex) { throw new Store.Rule("imageError"); }
                }
                values.put("image", imagePath[0]);
                store.save(entity, row == null ? null : row.number(entity.id), values); refresh();
            } catch (Exception ex) {
                if (imported != null) try { Files.deleteIfExists(imported); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                event.consume(); error.setText(message(ex));
            }
        });
        return dialog;
    }

    private void showImage(ImageView view, String path) {
        if (path.isBlank()) { view.setImage(null); return; }
        try {
            String uri = path;
            if (!path.startsWith("file:")) {
                if (path.contains(":\\") && !System.getProperty("os.name").startsWith("Windows")) {
                    String fileName = path.substring(path.lastIndexOf('\\') + 1);
                    Path legacy = Path.of("resources", "img", fileName);
                    if (Files.exists(legacy)) uri = legacy.toUri().toString(); else return;
                } else uri = Path.of(path).toUri().toString();
            }
            Image image = new Image(uri, 220, 220, true, true); view.setImage(image.isError() ? null : image);
        } catch (IllegalArgumentException ex) { view.setImage(null); }
    }

    private void loanPage() throws SQLException {
        HBox heading = heading("loans", "loansHint"); Button borrow = button("borrow", this::borrowDialog); borrow.getStyleClass().add("primary"); borrow.setId("borrow"); heading.getChildren().add(borrow);
        CheckBox history = new CheckBox(t("showHistory")); history.setId("showHistory");
        TextField search = searchField();
        TableView<Store.Row> table = loanTable(store.loans(false, null, null)); table.setId("loansTable");
        Runnable filter = () -> run(() -> {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            table.getItems().setAll(store.loans(history.isSelected(), null, null).stream().filter(row -> SearchText.matches(row, query)).toList());
        });
        history.setOnAction(e -> filter.run()); search.textProperty().addListener((o, a, b) -> filter.run());
        Button returned = button("return", () -> selected(table, row -> { if (confirm("returnConfirm")) run(() -> { store.returnLoan(row.number("emp_id")); filter.run(); }); }));
        returned.setId("return"); returned.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> table.getSelectionModel().getSelectedItem() == null || table.getSelectionModel().getSelectedItem().text("emp_state").equals("0"), table.getSelectionModel().selectedItemProperty()));
        HBox tools = new HBox(14, search, history, button("refresh", () -> filter.run())); tools.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(search, Priority.ALWAYS);
        content.getChildren().addAll(heading, tools, table, returned);
    }

    private TableView<Store.Row> loanTable(List<Store.Row> rows) {
        TableView<Store.Row> table = table();
        column(table, "document", "doc_libelle", 220); column(table, "student", "student", 160); column(table, "cne", "cne", 100);
        column(table, "borrowDate", "emp_d", 115); column(table, "dueDate", "return_d", 115);
        column(table, "state", row -> t(row.text("emp_state").equals("0") ? "returned" : row.text("return_d").compareTo(LocalDate.now().toString()) < 0 ? "overdue" : "borrowed"), 120);
        table.getItems().setAll(rows); return table;
    }

    Dialog<ButtonType> newBorrowDialog() throws SQLException { return newBorrowDialog(null); }
    private Dialog<ButtonType> newBorrowDialog(Integer selectedBook) throws SQLException {
        Dialog<ButtonType> dialog = dialog(t("borrow"), true, "loans");
        ComboBox<Store.Row> books = new SearchableComboBox<>(store.choices(Store.Entity.BOOKS).stream().filter(row -> row.text("borrowed").equals("0")).toList(), i18n);
        if (selectedBook != null) books.getItems().stream().filter(row -> row.number("doc_id") == selectedBook).findFirst().ifPresent(books::setValue);
        ComboBox<Store.Row> students = new SearchableComboBox<>(store.choices(Store.Entity.STUDENTS), i18n);
        books.setMaxWidth(Double.MAX_VALUE); students.setMaxWidth(Double.MAX_VALUE); books.setId("loan-book"); students.setId("loan-student");
        DatePicker due = new DatePicker(LocalDate.now().plusDays(14)); due.setEditable(false); due.setId("loan-due"); due.setMaxWidth(Double.MAX_VALUE);
        Label error = label("", "error-label"); error.setWrapText(true);
        Label heading = label(t("borrow"), "section-title");
        Label description = label(t("loanFormHint"), "muted"); description.setWrapText(true);
        HBox intro = new HBox(14, icon("loans"), new VBox(7, heading, description)); intro.setAlignment(Pos.CENTER_LEFT); intro.getStyleClass().add("form-intro");
        GridPane grid = new GridPane(); grid.setHgap(18); grid.setVgap(20);
        ColumnConstraints first = new ColumnConstraints(); first.setPercentWidth(50);
        ColumnConstraints second = new ColumnConstraints(); second.setPercentWidth(50); grid.getColumnConstraints().addAll(first, second);
        grid.add(formField("document", books, true), 0, 0, 2, 1);
        grid.add(formField("student", students, true), 0, 1, 2, 1);
        grid.add(formField("dueDate", due, true), 0, 2, 2, 1);
        VBox form = new VBox(22, intro, grid, label(t("requiredHint"), "muted"), error); form.setPrefWidth(580); form.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(form);
        Button save = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                if (books.getValue() == null || students.getValue() == null) throw new Store.Rule("required");
                store.borrow(books.getValue().number("doc_id"), students.getValue().number("etud_id"), due.getValue()); refresh();
            } catch (Exception ex) { event.consume(); error.setText(message(ex)); }
        });
        return dialog;
    }
    private void borrowDialog() { run(() -> newBorrowDialog().showAndWait()); }

    private void historyDialog(Store.Entity entity, Store.Row row) {
        run(() -> {
            Dialog<ButtonType> dialog = dialog(t("history"), false);
            VBox box = new VBox(16, loanTable(store.loans(true, entity, row.number(entity.id)))); box.setPrefWidth(840);
            if (entity == Store.Entity.STAFF && store.user().admin()) box.getChildren().add(auditTable(store.auditLog(row.number(entity.id))));
            dialog.getDialogPane().setContent(box); dialog.showAndWait();
        });
    }

    private void activityPage() throws SQLException { content.getChildren().addAll(heading("activity", "activityHint"), auditTable(store.auditLog(null))); }
    private TableView<Store.Row> auditTable(List<Store.Row> rows) {
        TableView<Store.Row> table = table(); column(table, "date", "date", 210); column(table, "account", "cin", 160); column(table, "action", row -> auditDescription(row.text("info")), 450); table.getItems().setAll(rows); return table;
    }
    private String auditDescription(String info) {
        // Keep legacy free-form audit messages verbatim; translate structured new events.
        String[] parts = info.split(" ", 3);
        if (parts.length == 1) return switch (info) { case "login" -> t("signIn"); case "logout" -> t("logout"); default -> info; };
        String verb = switch (parts[0]) { case "create" -> "add"; case "update" -> "edit"; case "delete" -> "delete"; case "borrow" -> "borrow"; case "return" -> "return"; default -> null; };
        String entity = switch (parts[1]) { case "docs" -> "books"; case "etudiant" -> "students"; case "staff" -> "staff"; case "types" -> "types"; case "categories" -> "categories"; case "emprunts" -> "loans"; default -> null; };
        return verb == null || entity == null ? info : t(verb) + " · " + t(entity) + (parts.length > 2 ? " · " + parts[2].replace("etudiant", t("student")) : "");
    }

    private void settingsPage() {
        content.getChildren().add(heading("settings", "settingsHint"));
        VBox appearance = new VBox(20, label(t("language") + " / " + t("theme"), "section-title"), preferencesBar()); appearance.getStyleClass().add("card");
        TextField path = new TextField(store.directory.toString()); path.setEditable(false); path.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        Label hint = label(t("storageHint"), "muted"); hint.setWrapText(true);
        VBox storage = new VBox(14, label(t("storage"), "section-title"), path, hint); storage.getStyleClass().add("card");
        content.getChildren().addAll(appearance, storage, label("Papyrus · " + t("version"), "muted"));
    }

    private HBox heading(String title, String hint) {
        Label description = label(t(hint), "muted"); description.setWrapText(true);
        VBox text = new VBox(8, label(t(title), "page-title"), description);
        HBox box = new HBox(16, text, spacer()); box.setAlignment(Pos.CENTER_LEFT); return box;
    }
    private HBox brand() { HBox box = new HBox(10, icon("brand"), label("Papyrus", "wordmark")); box.setAlignment(Pos.CENTER_LEFT); return box; }
    private Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private Button button(String key, Runnable action) { Button button = new Button(t(key)); button.setOnAction(e -> action.run()); return button; }
    private VBox field(String key, Control control) { Label label = label(t(key), "field-label"); label.setLabelFor(control); return new VBox(7, label, control); }
    private VBox formField(String key, Control control, boolean required) {
        Label label = label(t(key) + (required ? " *" : ""), "field-label"); label.setLabelFor(control);
        VBox field = new VBox(9, label, control); field.setMinWidth(0); return field;
    }
    private TextField searchField() { TextField field = new TextField(searchValue); field.setPromptText(t("search")); field.setId("search"); field.setAccessibleText(t("search")); return field; }
    private Region spacer() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); VBox.setVgrow(region, Priority.ALWAYS); return region; }
    private VBox empty(String hint) { Label label = label(t(hint), "muted"); label.setWrapText(true); VBox box = new VBox(12, icon("books"), label); box.setAlignment(Pos.CENTER); box.setPadding(new Insets(28)); return box; }
    private TableView<Store.Row> table() { TableView<Store.Row> table = new TableView<>(); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); table.setPlaceholder(empty("emptyHint")); table.setPrefHeight(430); VBox.setVgrow(table, Priority.ALWAYS); return table; }
    private void column(TableView<Store.Row> table, String title, String field, double width) { column(table, title, row -> row.text(field), width); }
    private void column(TableView<Store.Row> table, String title, java.util.function.Function<Store.Row, String> value, double width) {
        TableColumn<Store.Row, String> column = new TableColumn<>(t(title)); column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue()))); column.setPrefWidth(width); table.getColumns().add(column);
    }
    private void selected(TableView<Store.Row> table, Consumer<Store.Row> action) { Store.Row row = table.getSelectionModel().getSelectedItem(); if (row != null) action.accept(row); }
    private Dialog<ButtonType> dialog(String title, boolean save) {
        return dialog(title, save, page);
    }
    private Dialog<ButtonType> dialog(String title, boolean save, String icon) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(title); dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType(t(save ? "save" : "close"), ButtonBar.ButtonData.OK_DONE));
        if (save) dialog.getDialogPane().getButtonTypes().add(new ButtonType(t("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        if (save) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst()).getStyleClass().add("primary");
        Runnable setIcon = () -> { if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof Stage window) window.getIcons().setAll(windowIcon(icon)); };
        dialog.setOnShowing(event -> setIcon.run()); dialog.setOnShown(event -> setIcon.run());
        setIcon.run();
        apply(dialog.getDialogPane()); return dialog;
    }
    private boolean confirm(String key) {
        ButtonType yes = new ButtonType(t("ok"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, t(key), yes, new ButtonType(t("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        alert.initOwner(stage); alert.setTitle(t("confirm")); alert.setHeaderText(null); apply(alert.getDialogPane());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == yes;
    }
    private void refresh() { try { renderPage(); } catch (Exception ex) { showError(ex); } }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private void run(Checked action) { try { action.run(); } catch (Exception ex) { showError(ex); } }
    private String message(Exception ex) {
        if (ex instanceof Store.Rule rule) return t(rule.key);
        if (ex instanceof SQLException sql && sql.getErrorCode() == 19) return t("duplicate");
        ex.printStackTrace(); return t("databaseError");
    }
    private void showError(Exception ex) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message(ex), new ButtonType(t("ok"), ButtonBar.ButtonData.OK_DONE));
        alert.initOwner(stage); alert.setTitle(t("error")); alert.setHeaderText(null); apply(alert.getDialogPane()); alert.showAndWait();
    }

    private Node icon(String name) {
        String path = switch (name) {
            case "brand" -> "M3 15 Q7 13 12 17 Q17 13 21 15 V21 Q17 19 12 23 Q7 19 3 21 Z M12 17 C6 12 6 6 8 2 C15 3 18 9 12 17 Z M12 17 L10 6";
            case "books" -> "M3 4 Q8 2 12 5 Q16 2 21 4 L21 20 Q16 18 12 21 Q8 18 3 20 Z M12 5 L12 21";
            case "dashboard" -> "M3 3 H10 V10 H3 Z M14 3 H21 V10 H14 Z M3 14 H10 V21 H3 Z M14 14 H21 V21 H14 Z";
            case "students" -> "M8 3 A4 4 0 1 1 7.99 3 M2 21 V18 Q2 12 8 12 Q14 12 14 18 V21 M17 4 Q23 6 18 11 M18 14 Q23 14 23 21";
            case "staff" -> "M3 4 H21 V20 H3 Z M8 8 A2 2 0 1 1 7.99 8 M5 17 Q5 12 8 12 Q11 12 11 17 M14 9 H18 M14 13 H18 M14 17 H18";
            case "loans" -> "M3 7 H20 L16 3 M21 17 H4 L8 21 M20 7 L16 11 M4 17 L8 13";
            case "types" -> "M4 3 H15 L21 9 V21 H4 Z M15 3 V9 H21 M8 13 H17 M8 17 H15";
            case "categories" -> "M3 6 H10 L12 9 H21 V21 H3 Z";
            case "activity" -> "M2 12 H6 L9 4 L14 20 L17 12 H22";
            case "settings" -> "M3 6 H21 M3 12 H21 M3 18 H21 M8 3 V9 M16 9 V15 M10 15 V21";
            case "sun" -> "M12 7 A5 5 0 1 1 11.99 7 M12 1 V4 M12 20 V23 M1 12 H4 M20 12 H23 M4 4 L6 6 M18 18 L20 20 M4 20 L6 18 M18 6 L20 4";
            case "moon" -> "M20 15 A9 9 0 1 1 9 3 A8 8 0 0 0 20 15";
            default -> "M3 12 H21";
        };
        SVGPath svg = new SVGPath(); svg.setContent(path); svg.getStyleClass().add("line-icon");
        StackPane box = new StackPane(svg); box.setMinSize(24, 24); box.setPrefSize(24, 24); box.setMaxSize(24, 24); return box;
    }

    private Image windowIcon(String page) {
        return windowIcons.computeIfAbsent(page, key -> {
            StackPane graphic = (StackPane) icon(key);
            SVGPath path = (SVGPath) graphic.getChildren().getFirst();
            path.setStroke(javafx.scene.paint.Color.WHITE); path.setStrokeWidth(1.8);
            path.setFill(javafx.scene.paint.Color.TRANSPARENT); path.setScaleX(1.8); path.setScaleY(1.8);
            StackPane tile = new StackPane(graphic); tile.setStyle("-fx-background-color: #21765b; -fx-background-radius: 12;");
            tile.resize(64, 64); tile.layout();
            return tile.snapshot(new SnapshotParameters(), new WritableImage(64, 64));
        });
    }
}
