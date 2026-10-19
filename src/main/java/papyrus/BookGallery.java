package papyrus;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;

/** Recycles visible rows instead of constructing one scene graph and image per library item. */
final class BookGallery extends ListView<List<Store.Row>> {
    private final ObjectProperty<Store.Row> selectedBook = new SimpleObjectProperty<>();
    private List<Store.Row> records = List.of();
    private int columns = 1;
    private final Map<String, Image> images = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> entry) { return size() > 64; }
    };

    BookGallery(I18n i18n, Path directory, Supplier<Node> bookIcon, Consumer<Store.Row> edit, Consumer<Store.Row> borrow) {
        setId("book-gallery"); getStyleClass().add("book-gallery"); setFixedCellSize(390); setPrefHeight(490);
        selectedBook.addListener((o, before, after) -> refresh());
        widthProperty().addListener((o, before, after) -> {
            int count = Math.max(1, (int) ((after.doubleValue() - 24) / 228));
            if (count != columns) { columns = count; regroup(); }
        });
        setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(List<Store.Row> row, boolean empty) {
                super.updateItem(row, empty); setText(null); setGraphic(null);
                if (empty || row == null) return;
                HBox line = new HBox(16); line.setAlignment(Pos.TOP_LEFT);
                for (Store.Row book : row) {
                    ImageView image = new ImageView(); image.setFitWidth(174); image.setFitHeight(185); image.setPreserveRatio(true);
                    Path path = ImageStorage.resolve(book.text("image"), directory);
                    if (path != null) {
                        String uri = path.toUri().toString();
                        Image thumbnail = images.computeIfAbsent(uri, key -> new Image(key, 220, 220, true, true, true));
                        image.setImage(thumbnail.isError() ? null : thumbnail);
                    }
                    Label noImage = new Label(i18n.text("noImage")); noImage.getStyleClass().add("muted");
                    VBox placeholder = new VBox(10, bookIcon.get(), noImage); placeholder.setAlignment(Pos.CENTER); placeholder.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> image.getImage() == null || image.getImage().isError(), image.imageProperty(), image.getImage() == null ? image.imageProperty() : image.getImage().errorProperty()));
                    StackPane cover = new StackPane(placeholder, image); cover.getStyleClass().add("book-cover"); cover.setMinHeight(195); cover.setPrefHeight(195);
                    Label title = new Label(book.text("doc_libelle")); title.getStyleClass().add("book-title"); title.setWrapText(true); title.setMinHeight(36); title.setMaxHeight(36);
                    title.setTooltip(new Tooltip(book.text("doc_libelle")));
                    String detail = book.text("author").isBlank() ? book.text("cat_libelle") + " · " + book.text("ty_libelle") : book.text("author");
                    Label author = new Label(detail); author.getStyleClass().add("muted"); author.setMaxWidth(180);
                    Label copy = new Label(book.text("copy_code") + (book.text("shelf").isBlank() ? "" : " · " + book.text("shelf"))); copy.getStyleClass().add("muted"); copy.setMaxWidth(180);
                    Label state = new Label(i18n.text(book.text("borrowed").equals("1") ? "borrowed" : "available")); state.getStyleClass().add("book-status");
                    Button lend = new Button(i18n.text("borrow")); lend.getStyleClass().add("quiet"); lend.setMaxWidth(Double.MAX_VALUE); lend.setDisable(book.text("borrowed").equals("1")); lend.setOnAction(e -> borrow.accept(book));
                    VBox card = new VBox(6, cover, title, author, copy, state, lend); card.getStyleClass().add("book-card"); card.setMinWidth(210); card.setPrefWidth(210); card.setMaxWidth(210);
                    card.setFocusTraversable(true); card.setAccessibleText(book.text("doc_libelle"));
                    card.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), Objects.equals(selectedBook.get(), book));
                    card.setOnMouseClicked(e -> { selectedBook.set(book); if (e.getClickCount() == 2) edit.accept(book); });
                    card.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { selectedBook.set(book); e.consume(); } });
                    line.getChildren().add(card);
                }
                setGraphic(line);
            }
        });
    }
    ObjectProperty<Store.Row> selectedBookProperty() { return selectedBook; }
    void setRecords(List<Store.Row> values) { records = List.copyOf(values); selectedBook.set(null); regroup(); }
    private void regroup() {
        List<List<Store.Row>> groups = new ArrayList<>();
        for (int i = 0; i < records.size(); i += columns) groups.add(records.subList(i, Math.min(records.size(), i + columns)));
        setItems(FXCollections.observableArrayList(groups));
    }
}
