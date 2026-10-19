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
    private final UiTasks tasks = new UiTasks();
    private final LibraryTools tools = new LibraryTools(this);
    private int generation;
    private final Map<String, Filters> pageFilters = new HashMap<>();
    private static final class Filters {
        String search = ""; int availability, role; Integer category, type; boolean history, overdue;
    }
    private Filters filters() { return pageFilters.computeIfAbsent(page, ignored -> new Filters()); }
    I18n i18n() { return i18n; }
    UiTasks tasks() { return tasks; }
    Stage window() { return stage; }

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

    @Override public void stop() throws Exception { stopCarousel(); tasks.close(); if (store != null) store.close(); }
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
            stopCarousel(); generation++;
            pageFilters.clear(); searchValue = "";
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
                if (setup && !password.getText().equals(confirm.getText())) {
                    error.setText(t("passwordMismatch")); error.setVisible(true); error.setManaged(true); return;
                }
                String loginValue = username.getText(), nameValue = name.getText(), passwordValue = password.getText();
                pane.setDisable(true);
                tasks.submit(() -> {
                    if (setup) store.setup(loginValue, nameValue, passwordValue);
                    return store.login(loginValue, passwordValue);
                }, user -> { pane.setDisable(false); password.clear(); confirm.clear(); page = "dashboard"; showWorkspace(); }, ex -> {
                    pane.setDisable(false); error.setText(message(ex)); error.setVisible(true); error.setManaged(true);
                });
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
        VBox navigation = new VBox(8);
        ScrollPane navigationScroll = new ScrollPane(navigation); navigationScroll.setFitToWidth(true); navigationScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); navigationScroll.setMinHeight(0);
        VBox.setVgrow(navigationScroll, Priority.ALWAYS); sidebar.setMinHeight(0);
        sidebar.getChildren().addAll(brand(), section, navigationScroll);
        for (String key : List.of("dashboard", "books", "students", "loans", "types", "categories", "staff", "activity")) {
            if ((key.equals("staff") || key.equals("activity")) && !store.user().admin()) continue;
            Button nav = button(key, () -> navigate(key)); nav.setGraphic(icon(key)); nav.setId("nav-" + key);
            nav.getStyleClass().add("nav"); if (page.equals(key)) nav.getStyleClass().add("selected");
            nav.setMaxWidth(Double.MAX_VALUE); nav.setAlignment(Pos.CENTER_LEFT); navigation.getChildren().add(nav);
        }
        Button settings = button("settings", () -> navigate("settings")); settings.setGraphic(icon("settings")); settings.getStyleClass().add("nav"); settings.setId("nav-settings"); settings.setMaxWidth(Double.MAX_VALUE);
        if (page.equals("settings")) settings.getStyleClass().add("selected");
        Label user = label(store.user().name(), "account-name"); user.setWrapText(true); user.setMaxHeight(40); user.setTooltip(new Tooltip(store.user().name()));
        Label role = label(t(store.user().admin() ? "admin" : "staffRole"), "muted");
        Button logout = button("logout", () -> tasks.submit(() -> { store.logout(); return true; }, done -> showLogin(), ex -> { showLogin(); showError(ex); }));
        logout.getStyleClass().add("quiet"); logout.setId("logout");
        navigation.getChildren().add(settings);
        sidebar.getChildren().addAll(new Separator(), user, role, logout);
        pane.setLeft(sidebar);

        VBox workspace = new VBox();
        HBox top = new HBox(label(t("localFirst"), "muted"), spacer(), preferencesBar()); top.getStyleClass().add("topbar"); top.setAlignment(Pos.CENTER_LEFT);
        content = new VBox(22); content.getStyleClass().add("content"); content.setFillWidth(true);
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.setFitToHeight(true); scroll.getStyleClass().add("workspace-scroll");
        workspace.getChildren().addAll(top, scroll); VBox.setVgrow(scroll, Priority.ALWAYS);
        pane.setCenter(workspace); setRoot(pane);
        try { renderPage(); } catch (Exception ex) { showError(ex); }
    }

    void navigate(String page) { this.page = page; searchValue = filters().search; showWorkspace(); }

    private void renderPage() throws SQLException {
        content.getChildren().clear(); generation++;
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

    private record DashboardData(Map<String, Integer> totals, List<Store.Row> popular, List<Store.Row> recent) {}
    private void dashboard() {
        int request = generation;
        HBox heading = heading("dashboard", "dashboardHint"); heading.getChildren().add(button("borrow", this::borrowDialog));
        ProgressIndicator loading = new ProgressIndicator(); loading.setMaxSize(32, 32); content.getChildren().addAll(heading, loading);
        tasks.submit(() -> new DashboardData(store.statistics(), store.popularBooks(), store.loans(true, null, null, 8)), data -> {
            if (request != generation) return;
            content.getChildren().remove(loading);
            GridPane cards = new GridPane(); cards.setHgap(14);
            String[] labels = {"totalDocuments", "totalStudents", "activeLoansLabel", "overdue"};
            String[] keys = {"books", "students", "loans", "overdue"};
            for (int i = 0; i < keys.length; i++) {
                VBox card = new VBox(14, label(t(labels[i]), "muted"), label(Integer.toString(data.totals().get(keys[i])), "stat-value"));
                card.getStyleClass().add("card"); card.setMinWidth(0); if (i == 3) card.getStyleClass().add("overdue-card");
                GridPane.setHgrow(card, Priority.ALWAYS); cards.add(card, i, 0); ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(25); cards.getColumnConstraints().add(column);
            }
            content.getChildren().add(cards);
            CategoryAxis names = new CategoryAxis(); NumberAxis counts = new NumberAxis(); counts.setTickUnit(1); counts.setMinorTickVisible(false);
            BarChart<String, Number> chart = new BarChart<>(names, counts); chart.setLegendVisible(false); chart.setAnimated(false); chart.setPrefHeight(230);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (Store.Row row : data.popular()) series.getData().add(new XYChart.Data<>(row.text("doc_libelle"), row.number("total")));
            chart.getData().add(series);
            VBox chartCard = new VBox(12, label(t("popularBooks"), "section-title"), series.getData().isEmpty() ? empty("noActivity") : chart); chartCard.getStyleClass().add("card");
            TableView<Store.Row> recent = loanTable(data.recent()); recent.setPrefHeight(260);
            content.getChildren().addAll(chartCard, label(t("recentLoans"), "section-title"), recent);
        }, ex -> { if (request == generation) showError(ex); });
    }

    private void entityPage(Store.Entity entity, String hint) {
        int request = generation; Filters state = filters();
        HBox heading = heading(page, hint); Button add = button("add", () -> editor(entity, null).showAndWait()); add.setId("add"); add.getStyleClass().add("primary"); heading.getChildren().add(add);
        TableView<Store.Row> table = table(); table.setId("records");
        switch (entity) {
            case STUDENTS -> { column(table, "cne", "cne", 120); column(table, "prenom", "prenom", 150); column(table, "nom", "nom", 150); column(table, "phone", "phone", 140); column(table, "email", "email", 190); }
            case STAFF -> { column(table, "cin", "cin", 180); column(table, "prenom", "prenom", 140); column(table, "nom", "nom", 140); column(table, "phone", "phone", 130); column(table, "role", row -> t(row.text("role").equals("1") ? "admin" : "staffRole"), 150); }
            case TYPES -> column(table, "ty_libelle", "ty_libelle", 500);
            case CATEGORIES -> column(table, "cat_libelle", "cat_libelle", 500);
            default -> throw new IllegalArgumentException("Use the document gallery");
        }
        TextField search = searchField(); search.setText(state.search);
        var source = FXCollections.<Store.Row>observableArrayList(); FilteredList<Store.Row> filtered = new FilteredList<>(source);
        ComboBox<String> roles = new ComboBox<>(FXCollections.observableArrayList(t("all"), t("admin"), t("staffRole"))); roles.getSelectionModel().select(state.role); roles.setAccessibleText(t("role"));
        Label count = label("", "muted");
        Runnable filter = () -> {
            state.search = search.getText(); searchValue = state.search; state.role = roles.getSelectionModel().getSelectedIndex();
            filtered.setPredicate(row -> SearchText.matches(row, state.search) && (entity != Store.Entity.STAFF || state.role == 0 || row.text("role").equals(Integer.toString(state.role))));
            table.setPlaceholder(empty(state.search.isBlank() ? "emptyHint" : "noResults")); count.setText(filtered.size() + " " + t("recordCount"));
        };
        debounce(search, filter); roles.setOnAction(e -> filter.run());
        var sorted = new javafx.collections.transformation.SortedList<>(filtered); sorted.comparatorProperty().bind(table.comparatorProperty()); table.setItems(sorted);
        FlowPane toolbar = new FlowPane(10, 10, search); if (entity == Store.Entity.STAFF) toolbar.getChildren().add(roles); toolbar.getChildren().add(button("refresh", this::refresh));
        Button edit = button("edit", () -> selected(table, row -> editor(entity, row).showAndWait())); edit.setId("edit");
        Button delete = button("delete", () -> selected(table, row -> { if (confirm("deleteConfirm")) tools.work("delete", () -> { store.delete(entity, row.number(entity.id)); return true; }, done -> refresh()); })); delete.getStyleClass().add("danger"); delete.setId("delete");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()); delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        FlowPane actions = new FlowPane(10, 10, edit, delete, button("archived", () -> tools.archived(entity)));
        if (entity == Store.Entity.STUDENTS || entity == Store.Entity.STAFF) {
            Button history = button("history", () -> selected(table, row -> historyDialog(entity, row))); history.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull()); actions.getChildren().add(history);
        }
        actions.getChildren().add(count);
        table.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) editor(entity, table.getSelectionModel().getSelectedItem()).showAndWait(); });
        content.getChildren().addAll(heading, toolbar, table, actions);
        if (entity == Store.Entity.STUDENTS) content.getChildren().add(tools.transferButtons(entity));
        tasks.submit(() -> store.list(entity), rows -> { if (request == generation) { source.setAll(rows); filter.run(); } }, ex -> { if (request == generation) showError(ex); });
    }

    private record CatalogData(List<Store.Row> books, List<Store.Row> categories, List<Store.Row> types, Map<Store.Row, String> search) {}
    private void booksPage() {
        int request = generation; Filters state = filters();
        HBox heading = heading("books", "documentsHint"); Button add = button("add", () -> editor(Store.Entity.BOOKS, null).showAndWait()); add.setId("add"); add.getStyleClass().add("primary"); heading.getChildren().add(add);
        TextField search = searchField(); search.setText(state.search);
        ComboBox<String> availability = new ComboBox<>(FXCollections.observableArrayList(t("all"), t("available"), t("borrowed"))); availability.getSelectionModel().select(state.availability); availability.setAccessibleText(t("filterAvailability"));
        ComboBox<Store.Row> category = new SearchableComboBox<>(List.of(), i18n), type = new SearchableComboBox<>(List.of(), i18n);
        category.setPromptText(t("cat_libelle")); type.setPromptText(t("ty_libelle")); category.setAccessibleText(t("cat_libelle")); type.setAccessibleText(t("ty_libelle")); category.setPrefWidth(170); type.setPrefWidth(170);
        FlowPane toolbar = new FlowPane(10, 10, search, availability, category, type, button("refresh", this::refresh));
        BookGallery gallery = new BookGallery(i18n, store.directory, () -> icon("books"), row -> editor(Store.Entity.BOOKS, row).showAndWait(), row -> run(() -> newBorrowDialog(row.number("doc_id")).showAndWait()));
        VBox.setVgrow(gallery, Priority.ALWAYS); Label count = label(t("working"), "muted");
        CatalogData[] loaded = {null}; boolean[] restoringFilters = {false};
        Runnable filter = () -> {
            if (restoringFilters[0] || loaded[0] == null) return;
            state.search = search.getText(); searchValue = state.search; state.availability = availability.getSelectionModel().getSelectedIndex();
            state.category = category.getValue() == null ? null : category.getValue().number("cat_id"); state.type = type.getValue() == null ? null : type.getValue().number("type_id");
            String[] tokens = SearchText.normalize(state.search).strip().split("\\s+");
            List<Store.Row> visible = loaded[0].books().stream().filter(row -> Arrays.stream(tokens).allMatch(loaded[0].search().get(row)::contains)
                    && (state.availability == 0 || row.text("borrowed").equals(state.availability == 1 ? "0" : "1"))
                    && (state.category == null || Integer.toString(state.category).equals(row.text("cat_id"))) && (state.type == null || Integer.toString(state.type).equals(row.text("type_id")))).toList();
            gallery.setRecords(visible); gallery.setPlaceholder(empty(state.search.isBlank() ? "emptyHint" : "noResults")); count.setText(visible.size() + " " + t("recordCount"));
        };
        debounce(search, filter); availability.setOnAction(e -> filter.run()); category.setOnAction(e -> filter.run()); type.setOnAction(e -> filter.run());
        var selected = gallery.selectedBookProperty();
        Button edit = button("edit", () -> editor(Store.Entity.BOOKS, selected.get()).showAndWait()); edit.setId("edit"); edit.disableProperty().bind(selected.isNull());
        Button delete = button("delete", () -> { Store.Row row = selected.get(); if (row != null && confirm("deleteConfirm")) tools.work("delete", () -> { store.delete(Store.Entity.BOOKS, row.number("doc_id")); return true; }, done -> refresh()); }); delete.getStyleClass().add("danger"); delete.disableProperty().bind(selected.isNull());
        Button history = button("history", () -> historyDialog(Store.Entity.BOOKS, selected.get())); history.disableProperty().bind(selected.isNull());
        FlowPane actions = new FlowPane(10, 10, edit, delete, history, button("archived", () -> tools.archived(Store.Entity.BOOKS)), count);
        content.getChildren().addAll(heading, toolbar, gallery, actions, tools.transferButtons(Store.Entity.BOOKS));
        tasks.submit(() -> {
            List<Store.Row> books = store.list(Store.Entity.BOOKS); Map<Store.Row, String> index = new HashMap<>(); books.forEach(row -> index.put(row, SearchText.index(row)));
            return new CatalogData(books, store.choices(Store.Entity.CATEGORIES), store.choices(Store.Entity.TYPES), index);
        }, data -> {
            if (request != generation) return;
            loaded[0] = data; restoringFilters[0] = true; category.getItems().setAll(data.categories()); type.getItems().setAll(data.types());
            data.categories().stream().filter(row -> Objects.equals(state.category, row.number("cat_id"))).findFirst().ifPresent(category::setValue);
            data.types().stream().filter(row -> Objects.equals(state.type, row.number("type_id"))).findFirst().ifPresent(type::setValue);
            restoringFilters[0] = false; filter.run();
        }, ex -> { if (request == generation) showError(ex); });
    }

    Dialog<ButtonType> editor(Store.Entity entity, Store.Row row) { return RecordEditor.create(this, entity, row, id -> refresh()); }

    private void debounce(TextField field, Runnable action) {
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(160));
        Filters state = filters();
        delay.setOnFinished(e -> action.run());
        field.textProperty().addListener((o, before, after) -> { state.search = after; delay.playFromStart(); });
        field.sceneProperty().addListener((o, before, after) -> { if (after == null) delay.stop(); });
    }

    void showImage(ImageView view, String reference) {
        Path path = ImageStorage.resolve(reference, store.directory);
        if (path == null || !Files.isRegularFile(path)) { view.setImage(null); return; }
        try { Image image = new Image(path.toUri().toString(), 220, 220, true, true); view.setImage(image.isError() ? null : image); }
        catch (IllegalArgumentException ex) { view.setImage(null); }
    }

    private void loanPage() {
        int request = generation; Filters state = filters();
        HBox heading = heading("loans", "loansHint"); Button borrow = button("borrow", this::borrowDialog); borrow.getStyleClass().add("primary"); borrow.setId("borrow"); heading.getChildren().add(borrow);
        CheckBox history = new CheckBox(t("showHistory")); history.setId("showHistory"); history.setSelected(state.history);
        CheckBox overdue = new CheckBox(t("overdueOnly")); overdue.setId("overdue-only"); overdue.setSelected(state.overdue);
        TextField search = searchField(); search.setText(state.search);
        TableView<Store.Row> table = loanTable(List.of()); table.setId("loansTable");
        var rows = FXCollections.<Store.Row>observableArrayList();
        Runnable filter = () -> {
            state.search = search.getText(); state.history = history.isSelected(); state.overdue = overdue.isSelected();
            table.getItems().setAll(rows.stream().filter(row -> SearchText.matches(row, state.search)
                    && (state.history || row.text("emp_state").equals("1"))
                    && (!state.overdue || row.text("emp_state").equals("1") && row.text("return_d").compareTo(LocalDate.now().toString()) < 0)).toList());
        };
        Runnable load = () -> tasks.submit(() -> store.loans(true, null, null), result -> { if (request == generation) { rows.setAll(result); filter.run(); } }, ex -> { if (request == generation) showError(ex); });
        history.setOnAction(e -> filter.run()); overdue.setOnAction(e -> filter.run()); debounce(search, filter);
        Button returned = button("return", () -> selected(table, row -> { if (confirm("returnConfirm")) tools.work("return", () -> { store.returnLoan(row.number("emp_id")); return true; }, done -> load.run()); })); returned.setId("return");
        var unavailable = javafx.beans.binding.Bindings.createBooleanBinding(() -> table.getSelectionModel().getSelectedItem() == null || table.getSelectionModel().getSelectedItem().text("emp_state").equals("0"), table.getSelectionModel().selectedItemProperty());
        returned.disableProperty().bind(unavailable);
        Button renew = button("renew", () -> selected(table, row -> renewDialog(row, load))); renew.setId("renew"); renew.disableProperty().bind(unavailable);
        FlowPane toolbar = new FlowPane(10, 10, search, history, overdue, button("refresh", load));
        FlowPane actions = new FlowPane(10, 10, returned, renew, button("exportVisibleCsv", () -> tools.exportLoans(table.getItems())), button("printOverdue", tools::printOverdue));
        content.getChildren().addAll(heading, toolbar, table, actions); load.run();
    }

    private void renewDialog(Store.Row loan, Runnable refreshed) {
        Dialog<ButtonType> dialog = dialog(t("renew"), true, "loans");
        LocalDate oldDue = LocalDate.parse(loan.text("return_d")); LocalDate base = oldDue.isAfter(LocalDate.now()) ? oldDue : LocalDate.now();
        DatePicker due = new DatePicker(base.plusDays(14)); due.setId("renew-due"); due.setEditable(false);
        Label error = label("", "error-label"); error.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(18, label(loan.text("doc_libelle") + " · " + loan.text("student"), "section-title"), formField("dueDate", due, true), error));
        Button save = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); LocalDate value = due.getValue(); dialog.getDialogPane().setDisable(true);
            tasks.submit(() -> { store.renewLoan(loan.number("emp_id"), value); return true; }, done -> { dialog.getDialogPane().setDisable(false); dialog.close(); refreshed.run(); }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(message(ex)); });
        }); dialog.showAndWait();
    }

    private TableView<Store.Row> loanTable(List<Store.Row> rows) {
        TableView<Store.Row> table = table();
        column(table, "document", "doc_libelle", 220); column(table, "copy_code", "copy_code", 110); column(table, "student", "student", 160); column(table, "cne", "cne", 100);
        column(table, "borrowDate", "emp_d", 115); column(table, "dueDate", "return_d", 115); column(table, "returnedAt", row -> row.text("returned_at").replace('T', ' '), 160);
        column(table, "state", row -> t(row.text("emp_state").equals("0") ? "returned" : row.text("return_d").compareTo(LocalDate.now().toString()) < 0 ? "overdue" : "borrowed"), 120);
        table.getItems().setAll(rows); return table;
    }

    Dialog<ButtonType> newBorrowDialog() throws SQLException { return newBorrowDialog(null); }
    private Dialog<ButtonType> newBorrowDialog(Integer selectedBook) throws SQLException {
        Dialog<ButtonType> dialog = dialog(t("borrow"), true, "loans");
        ComboBox<Store.Row> books = new SearchableComboBox<>(List.of(), i18n);
        ComboBox<Store.Row> students = new SearchableComboBox<>(List.of(), i18n);
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
        tasks.submit(() -> List.of(store.choices(Store.Entity.BOOKS), store.choices(Store.Entity.STUDENTS)), choices -> {
            books.getItems().setAll(choices.get(0).stream().filter(row -> row.text("borrowed").equals("0")).toList()); students.getItems().setAll(choices.get(1));
            if (selectedBook != null) books.getItems().stream().filter(row -> row.number("doc_id") == selectedBook).findFirst().ifPresent(books::setValue);
        }, ex -> error.setText(message(ex)));
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (books.getValue() == null || students.getValue() == null) { error.setText(t("required")); return; }
            int book = books.getValue().number("doc_id"), student = students.getValue().number("etud_id"); LocalDate value = due.getValue();
            dialog.getDialogPane().setDisable(true);
            tasks.submit(() -> { store.borrow(book, student, value); return true; }, done -> { dialog.getDialogPane().setDisable(false); dialog.close(); refresh(); }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(message(ex)); });
        });
        return dialog;
    }
    private void borrowDialog() { run(() -> newBorrowDialog().showAndWait()); }

    private void historyDialog(Store.Entity entity, Store.Row row) {
        Dialog<ButtonType> dialog = dialog(t("history"), false);
        TableView<Store.Row> loans = loanTable(List.of()); VBox box = new VBox(16, loans); box.setPrefWidth(840); dialog.getDialogPane().setContent(box);
        tasks.submit(() -> store.loans(true, entity, row.number(entity.id)), rows -> loans.getItems().setAll(rows), this::showError);
        if (entity == Store.Entity.STAFF && store.user().admin()) tasks.submit(() -> store.auditLog(row.number(entity.id)), rows -> box.getChildren().add(auditTable(rows)), this::showError);
        dialog.showAndWait();
    }

    private void activityPage() {
        int request = generation; TableView<Store.Row> table = auditTable(List.of()); content.getChildren().addAll(heading("activity", "activityHint"), table);
        tasks.submit(() -> store.auditLog(null), rows -> { if (request == generation) table.getItems().setAll(rows); }, ex -> { if (request == generation) showError(ex); });
    }
    private TableView<Store.Row> auditTable(List<Store.Row> rows) {
        TableView<Store.Row> table = table(); column(table, "date", "date", 210); column(table, "account", "cin", 160); column(table, "action", row -> auditDescription(row.text("info")), 450); table.getItems().setAll(rows); return table;
    }
    private String auditDescription(String info) {
        // Keep legacy free-form audit messages verbatim; translate structured new events.
        String[] parts = info.split(" ", 3);
        if (parts.length == 1) return switch (info) { case "login" -> t("signIn"); case "logout" -> t("logout"); case "passwordChanged" -> t("passwordChanged"); case "backupRestored" -> t("backupRestored"); default -> info; };
        String verb = switch (parts[0]) { case "create" -> "add"; case "update" -> "edit"; case "delete" -> "delete"; case "borrow" -> "borrow"; case "return" -> "return"; case "renew" -> "renew"; case "restore" -> "restore"; default -> null; };
        String entity = switch (parts[1]) { case "docs" -> "books"; case "etudiant" -> "students"; case "staff" -> "staff"; case "types" -> "types"; case "categories" -> "categories"; case "emprunts" -> "loans"; default -> null; };
        return verb == null || entity == null ? info : t(verb) + " · " + t(entity) + (parts.length > 2 ? " · " + parts[2].replace("etudiant", t("student")) : "");
    }

    private void settingsPage() {
        content.getChildren().add(heading("settings", "settingsHint"));
        VBox appearance = new VBox(20, label(t("language") + " / " + t("theme"), "section-title"), preferencesBar()); appearance.getStyleClass().add("card");
        TextField path = new TextField(store.directory.toString()); path.setEditable(false); path.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        Label hint = label(t("storageHint"), "muted"); hint.setWrapText(true);
        VBox storage = new VBox(14, label(t("storage"), "section-title"), path, hint); storage.getStyleClass().add("card");
        content.getChildren().addAll(appearance, storage, button("changePassword", tools::password));
        if (store.user().admin()) {
            Label backupHint = label(t("backupHint"), "muted"); backupHint.setWrapText(true);
            VBox backups = new VBox(14, label(t("backupAndRestore"), "section-title"), backupHint, new HBox(10, button("backup", tools::backup), button("restoreBackup", tools::restoreBackup))); backups.getStyleClass().add("card"); content.getChildren().add(backups);
        }
        content.getChildren().add(label("Papyrus · " + t("version"), "muted"));
    }

    private HBox heading(String title, String hint) {
        Label description = label(t(hint), "muted"); description.setWrapText(true);
        VBox text = new VBox(8, label(t(title), "page-title"), description);
        HBox box = new HBox(16, text, spacer()); box.setAlignment(Pos.CENTER_LEFT); return box;
    }
    private HBox brand() { HBox box = new HBox(10, icon("brand"), label("Papyrus", "wordmark")); box.setAlignment(Pos.CENTER_LEFT); return box; }
    Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    Button button(String key, Runnable action) { Button button = new Button(t(key)); button.setOnAction(e -> action.run()); return button; }
    private VBox field(String key, Control control) { Label label = label(t(key), "field-label"); label.setLabelFor(control); return new VBox(7, label, control); }
    VBox formField(String key, Control control, boolean required) {
        Label label = label(t(key) + (required ? " *" : ""), "field-label"); label.setLabelFor(control);
        VBox field = new VBox(9, label, control); field.setMinWidth(0); return field;
    }
    private TextField searchField() { TextField field = new TextField(searchValue); field.setPromptText(t("search")); field.setId("search"); field.setAccessibleText(t("search")); return field; }
    private Region spacer() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); VBox.setVgrow(region, Priority.ALWAYS); return region; }
    private VBox empty(String hint) {
        Label label = label(t(hint), "muted"); label.setWrapText(true);
        // Virtualized controls measure placeholders before sizing them. Keep Arabic text out of zero-width layout.
        label.setMinWidth(120); label.setPrefWidth(280);
        VBox box = new VBox(12, icon("books"), label); box.setAlignment(Pos.CENTER); box.setPadding(new Insets(28)); return box;
    }
    TableView<Store.Row> table() { TableView<Store.Row> table = new TableView<>(); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); table.setPlaceholder(empty("emptyHint")); table.setPrefHeight(430); VBox.setVgrow(table, Priority.ALWAYS); return table; }
    void column(TableView<Store.Row> table, String title, String field, double width) { column(table, title, row -> row.text(field), width); }
    void column(TableView<Store.Row> table, String title, java.util.function.Function<Store.Row, String> value, double width) {
        TableColumn<Store.Row, String> column = new TableColumn<>(t(title)); column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue()))); column.setPrefWidth(width); table.getColumns().add(column);
    }
    private void selected(TableView<Store.Row> table, Consumer<Store.Row> action) { Store.Row row = table.getSelectionModel().getSelectedItem(); if (row != null) action.accept(row); }
    Dialog<ButtonType> dialog(String title, boolean save) {
        return dialog(title, save, page);
    }
    Dialog<ButtonType> dialog(String title, boolean save, String icon) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(title); dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType(t(save ? "save" : "close"), ButtonBar.ButtonData.OK_DONE));
        if (save) dialog.getDialogPane().getButtonTypes().add(new ButtonType(t("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        if (save) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst()).getStyleClass().add("primary");
        Runnable setIcon = () -> {
            if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() instanceof Stage window) {
                javafx.beans.binding.Bindings.unbindContent(window.getIcons(), stage.getIcons());
                window.getIcons().setAll(windowIcon(icon));
            }
        };
        dialog.setOnShowing(event -> setIcon.run()); dialog.setOnShown(event -> setIcon.run());
        setIcon.run();
        apply(dialog.getDialogPane()); return dialog;
    }
    boolean confirm(String key) {
        ButtonType yes = new ButtonType(t("ok"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, t(key), yes, new ButtonType(t("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        alert.initOwner(stage); alert.setTitle(t("confirm")); alert.setHeaderText(null); apply(alert.getDialogPane());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == yes;
    }
    void refresh() { try { renderPage(); } catch (Exception ex) { showError(ex); } }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private void run(Checked action) { try { action.run(); } catch (Exception ex) { showError(ex); } }
    String message(Exception ex) {
        if (ex instanceof Store.ImportFailure failure) return t("importRecordError") + " " + failure.record + ": " + message((Exception) failure.getCause());
        if (ex instanceof Store.Rule rule) return t(rule.key);
        if (ex instanceof SQLException sql && sql.getErrorCode() == 19) return t("duplicate");
        ex.printStackTrace();
        if (ex instanceof IOException) return t("fileError") + "\n" + Objects.toString(ex.getMessage(), "");
        return t("databaseError");
    }
    void inform(String title, String details) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, details.isBlank() ? t(title) : details, new ButtonType(t("close"), ButtonBar.ButtonData.OK_DONE));
        alert.initOwner(stage); alert.setTitle(t(title)); alert.setHeaderText(t(title)); apply(alert.getDialogPane()); alert.showAndWait();
    }
    void showError(Exception ex) {
        if (store != null && store.user() == null && !(ex instanceof IOException)) showLogin();
        Alert alert = new Alert(Alert.AlertType.ERROR, message(ex), new ButtonType(t("ok"), ButtonBar.ButtonData.OK_DONE));
        alert.initOwner(stage); alert.setTitle(t("error")); alert.setHeaderText(null); apply(alert.getDialogPane()); alert.showAndWait();
    }

    Node icon(String name) {
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
        return windowIcons.computeIfAbsent(page, key -> new Image(Objects.requireNonNull(
                getClass().getResourceAsStream("/papyrus/icons/" + key + ".png"))));
    }
}
