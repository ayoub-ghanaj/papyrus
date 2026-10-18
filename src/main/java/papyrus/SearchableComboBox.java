package papyrus;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.util.StringConverter;
import java.util.*;

/** Explicit selection from searchable suggestions: typed text never becomes a record ID. */
final class SearchableComboBox<T> extends ComboBox<T> {
    private final I18n i18n;
    private final TextField searchEditor = new TextField();

    SearchableComboBox(List<T> items, I18n i18n) {
        super(FXCollections.observableArrayList(items));
        this.i18n = i18n;
        setEditable(false);
        setFocusTraversable(false);
        setPromptText(i18n.text("typeToSearch"));
        getStyleClass().add("search-picker");
        setConverter(new StringConverter<>() {
            @Override public String toString(T item) { return item == null ? "" : item.toString(); }
            @Override public T fromString(String text) { return getItems().stream().filter(item -> item.toString().equals(text)).findFirst().orElse(null); }
        });
    }

    @Override protected Skin<?> createDefaultSkin() { return new PickerSkin<>(this); }
    TextField searchEditor() { return searchEditor; }

    private static final class PickerSkin<T> extends SkinBase<SearchableComboBox<T>> {
        private final SearchableComboBox<T> control;
        private final TextField editor;
        private final Popup popup = new Popup();
        private final ListView<T> suggestions = new ListView<>();
        private final Label count = new Label();
        private final VBox panel;
        private boolean updating;
        private final ListChangeListener<T> itemsChanged = change -> filter();

        PickerSkin(SearchableComboBox<T> control) {
            super(control);
            this.control = control;
            editor = control.searchEditor(); editor.setPromptText(control.getPromptText()); editor.setAccessibleText(control.getAccessibleText());
            Button clear = new Button("×"); clear.getStyleClass().add("picker-clear"); clear.setAccessibleText(control.i18n.text("clearSelection")); clear.setFocusTraversable(false);
            clear.setOnAction(event -> { choose(null); editor.requestFocus(); control.show(); });
            Button arrow = new Button("⌄"); arrow.getStyleClass().add("picker-arrow"); arrow.setAccessibleText(control.i18n.text("suggestions")); arrow.setFocusTraversable(false);
            arrow.setOnAction(event -> { if (control.isShowing()) control.hide(); else { editor.requestFocus(); control.show(); } });
            HBox input = new HBox(editor, clear, arrow); input.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(editor, Priority.ALWAYS); getChildren().add(input);
            suggestions.setFixedCellSize(53); suggestions.setFocusTraversable(false);
            suggestions.setCellFactory(list -> new ListCell<>() {
                @Override protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty); setText(null); setGraphic(null);
                    if (empty || item == null) return;
                    Label title = new Label(item.toString()); title.getStyleClass().add("suggestion-title");
                    String detail = item instanceof Store.Row row ? String.join(" · ", List.of(row.text("phone"), row.text("email"), row.text("cat_libelle"), row.text("ty_libelle")).stream().filter(value -> !value.isBlank()).toList()) : "";
                    Label subtitle = new Label(detail); subtitle.getStyleClass().add("muted"); subtitle.setVisible(!detail.isBlank()); subtitle.setManaged(!detail.isBlank());
                    setGraphic(new VBox(4, title, subtitle));
                }
            });
            suggestions.setPlaceholder(new Label(control.i18n.text("noResults")));
            suggestions.setOnMouseClicked(event -> { T selected = suggestions.getSelectionModel().getSelectedItem(); if (selected != null) choose(selected); });
            count.getStyleClass().add("suggestion-count");
            panel = new VBox(suggestions, count); panel.getStyleClass().addAll("root", "suggestion-popup");
            panel.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/papyrus/app.css")).toExternalForm());
            popup.getContent().add(panel); popup.setAutoHide(true); popup.setHideOnEscape(true);
            popup.setOnAutoHide(event -> control.hide());
            registerChangeListener(control.showingProperty(), observable -> { if (control.isShowing()) show(); else popup.hide(); });
            registerChangeListener(control.valueProperty(), observable -> { if (!updating) { updating = true; editor.setText(Objects.toString(control.getValue(), "")); updating = false; filter(); } });
            registerChangeListener(control.promptTextProperty(), observable -> editor.setPromptText(control.getPromptText()));
            registerChangeListener(control.sceneProperty(), observable -> { if (control.getScene() == null) control.hide(); });
            editor.textProperty().addListener((observable, before, after) -> {
                if (updating) return;
                updating = true; control.setValue(null); updating = false;
                filter(); if (editor.isFocused()) control.show();
            });
            editor.setOnMouseClicked(event -> control.show());
            editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                KeyCode key = event.getCode();
                if (key == KeyCode.DOWN || key == KeyCode.UP) {
                    control.show();
                    if (key == KeyCode.DOWN) suggestions.getSelectionModel().selectNext(); else suggestions.getSelectionModel().selectPrevious();
                    suggestions.scrollTo(suggestions.getSelectionModel().getSelectedIndex()); event.consume();
                } else if (key == KeyCode.ENTER && control.isShowing()) {
                    T selected = suggestions.getSelectionModel().getSelectedItem();
                    if (selected == null && suggestions.getItems().size() == 1) selected = suggestions.getItems().getFirst();
                    if (selected != null) choose(selected); event.consume();
                } else if (key == KeyCode.ESCAPE && control.isShowing()) { control.hide(); event.consume(); }
            });
            control.getItems().addListener(itemsChanged);
            updating = true; editor.setText(Objects.toString(control.getValue(), "")); updating = false; filter();
        }

        private void filter() {
            String query = control.getValue() == null ? editor.getText() : "";
            suggestions.getItems().setAll(control.getItems().stream().filter(item -> SearchText.matches(item, query)).toList());
            suggestions.getSelectionModel().clearSelection();
            suggestions.setPrefHeight(Math.max(65, Math.min(7, suggestions.getItems().size()) * 53 + 2));
            count.setText(suggestions.getItems().size() + " " + control.i18n.text("suggestions") + " · " + control.i18n.text("searchHelp"));
            if (popup.isShowing()) popup.sizeToScene();
        }

        private void show() {
            Bounds bounds = control.localToScreen(control.getBoundsInLocal());
            if (bounds == null) return;
            panel.getStyleClass().removeAll("light", "dark");
            panel.getStyleClass().add(control.getScene().getRoot().getStyleClass().contains("dark") ? "dark" : "light");
            panel.setNodeOrientation(control.getEffectiveNodeOrientation()); panel.setPrefWidth(Math.max(300, bounds.getWidth()));
            filter();
            if (!popup.isShowing()) popup.show(control, bounds.getMinX(), bounds.getMaxY() + 4);
        }

        private void choose(T item) {
            updating = true; control.setValue(item); editor.setText(Objects.toString(item, "")); updating = false;
            control.hide(); filter(); editor.positionCaret(editor.getLength());
        }

        @Override public void dispose() { popup.hide(); control.getItems().removeListener(itemsChanged); super.dispose(); }
    }
}
