package papyrus;

import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Record forms own validation and pending images; Store owns transactions and authorization. */
final class RecordEditor {
    static Dialog<ButtonType> create(PapyrusApp app, Store.Entity entity, Store.Row row, IntConsumer saved) {
        String title = app.t(row == null ? "add" : "edit") + " · " + app.t(entity.name().toLowerCase(Locale.ROOT));
        Dialog<ButtonType> dialog = app.dialog(title, true, entity.name().toLowerCase(Locale.ROOT));
        VBox fields = new VBox(22); fields.setPadding(new Insets(8));
        Label hint = app.label(app.t("formHint"), "muted"); hint.setWrapText(true);
        HBox intro = new HBox(14, app.icon(entity.name().toLowerCase(Locale.ROOT)), new VBox(7, app.label(title, "section-title"), hint)); intro.setAlignment(Pos.CENTER_LEFT); intro.getStyleClass().add("form-intro");
        GridPane grid = new GridPane(); grid.setHgap(16); grid.setVgap(20);
        for (int i = 0; i < 2; i++) { ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(50); grid.getColumnConstraints().add(column); }
        VBox information = new VBox(16, app.label(app.t(entity == Store.Entity.STAFF ? "accountDetails" : "recordDetails"), "form-section"), grid); information.setMinWidth(330);
        HBox body = new HBox(22, information); HBox.setHgrow(information, Priority.ALWAYS); fields.getChildren().addAll(intro, body);
        Set<String> required = switch (entity) {
            case BOOKS -> Set.of("doc_libelle", "cat_id", "type_id");
            case STUDENTS -> Set.of("cne", "nom", "prenom", "phone");
            case STAFF -> row == null ? Set.of("cin", "nom", "prenom", "role", "pass") : Set.of("cin", "nom", "prenom", "role");
            case TYPES -> Set.of("ty_libelle"); case CATEGORIES -> Set.of("cat_libelle");
        };
        Map<String, Control> controls = new LinkedHashMap<>();
        String[] imageReference = {row == null ? "" : row.text("image")}; Path[] pending = {null};
        ImageView preview = new ImageView(); preview.setFitWidth(155); preview.setFitHeight(155); preview.setPreserveRatio(true); app.showImage(preview, imageReference[0]);
        Label error = app.label("", "error-label"); error.setWrapText(true);
        int position = 0;
        for (String key : entity.fields) {
            if (key.equals("image")) {
                Button choose = app.button("chooseImage", () -> {
                    FileChooser chooser = new FileChooser(); chooser.setTitle(app.t("chooseImage")); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(app.t("image"), "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
                    File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
                    if (file != null) {
                        try { ImageStorage.validate(file.toPath()); pending[0] = file.toPath(); app.showImage(preview, file.toURI().toString()); error.setText(""); }
                        catch (Exception ex) { error.setText(app.t("imageError")); }
                    }
                });
                Button remove = app.button("removeImage", () -> { pending[0] = null; imageReference[0] = ""; preview.setImage(null); });
                choose.setMaxWidth(Double.MAX_VALUE); remove.setMaxWidth(Double.MAX_VALUE); remove.getStyleClass().add("quiet");
                VBox placeholder = new VBox(10, app.icon(entity.name().toLowerCase(Locale.ROOT)), app.label(app.t("noImage"), "muted")); placeholder.setAlignment(Pos.CENTER); placeholder.visibleProperty().bind(preview.imageProperty().isNull());
                StackPane frame = new StackPane(placeholder, preview); frame.getStyleClass().add("image-frame"); frame.setMinHeight(180); frame.setPrefHeight(180);
                Label imageHint = app.label(app.t("imageHint"), "muted"); imageHint.setWrapText(true);
                VBox images = new VBox(10, app.label(app.t("image"), "form-section"), frame, choose, remove, imageHint); images.setMinWidth(185); images.setPrefWidth(185); images.setMaxWidth(185); body.getChildren().add(images); continue;
            }
            Control control; Button quickAdd = null;
            if (key.equals("cat_id") || key.equals("type_id")) {
                Store.Entity choiceEntity = key.equals("cat_id") ? Store.Entity.CATEGORIES : Store.Entity.TYPES;
                SearchableComboBox<Store.Row> combo = new SearchableComboBox<>(List.of(), app.i18n());
                app.tasks().submit(() -> app.store.choices(choiceEntity), choices -> {
                    combo.getItems().setAll(choices);
                    if (row != null) choices.stream().filter(item -> item.text(choiceEntity.id).equals(row.text(key))).findFirst().ifPresent(combo::setValue);
                }, ex -> error.setText(app.message(ex)));
                quickAdd = app.button("add", () -> create(app, choiceEntity, null, id -> app.tasks().submit(() -> app.store.choices(choiceEntity), choices -> {
                    combo.getItems().setAll(choices); choices.stream().filter(item -> item.number(choiceEntity.id) == id).findFirst().ifPresent(combo::setValue);
                }, ex -> error.setText(app.message(ex)))).showAndWait());
                quickAdd.setId("quick-add-" + key); quickAdd.setAccessibleText(app.t("add") + " " + app.t(choiceEntity.name().toLowerCase(Locale.ROOT)));
                control = combo;
            } else if (key.equals("role")) {
                ComboBox<String> combo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(app.t("admin"), app.t("staffRole")));
                combo.getSelectionModel().select(row != null && row.text(key).equals("1") ? 0 : 1); control = combo;
            } else {
                TextField text = key.equals("pass") ? new PasswordField() : new TextField();
                if (row != null && !key.equals("pass")) text.setText(row.text(key));
                if (key.equals("pass") && row != null) text.setPromptText(app.t("passwordHint")); control = text;
            }
            control.setId("field-" + key); control.setMaxWidth(Double.MAX_VALUE); controls.put(key, control);
            String labelKey = key.equals("cat_id") ? "cat_libelle" : key.equals("type_id") ? "ty_libelle" : key;
            control.setAccessibleText(app.t(labelKey));
            VBox field = app.formField(labelKey, control, required.contains(key));
            if (quickAdd != null) { field.getChildren().remove(control); HBox line = new HBox(8, control, quickAdd); HBox.setHgrow(control, Priority.ALWAYS); field.getChildren().add(line); }
            boolean wide = Set.of("doc_libelle", "ty_libelle", "cat_libelle", "pass", "cat_id", "type_id").contains(key);
            if (wide && position % 2 != 0) position++;
            grid.add(field, position % 2, position / 2, wide ? 2 : 1, 1); position += wide ? 2 : 1;
        }
        fields.getChildren().addAll(app.label(app.t("requiredHint"), "muted"), error);
        ScrollPane scroll = new ScrollPane(fields); scroll.setFitToWidth(true); scroll.setPrefViewportHeight(entity.fields.size() > 4 ? 470 : 220); scroll.setPrefViewportWidth(entity.fields.contains("image") ? 780 : 580);
        dialog.getDialogPane().setContent(scroll); dialog.getDialogPane().getStyleClass().add("editor-dialog");
        Button save = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); Map<String, String> values = new LinkedHashMap<>(); Control firstInvalid = null;
            for (var entry : controls.entrySet()) {
                Control control = entry.getValue(); String value;
                if (control instanceof TextInputControl text) value = text.getText();
                else if (entry.getKey().equals("role")) value = ((ComboBox<?>) control).getSelectionModel().getSelectedIndex() == 0 ? "1" : "2";
                else { Store.Row selected = (Store.Row) ((ComboBox<?>) control).getValue(); value = selected == null ? "" : selected.text(entry.getKey()); }
                values.put(entry.getKey(), value);
                boolean invalid = required.contains(entry.getKey()) && value.isBlank();
                control.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), invalid);
                if (invalid && firstInvalid == null) firstInvalid = control;
            }
            if (firstInvalid != null) { error.setText(app.t("required")); firstInvalid.requestFocus(); return; }
            if (row != null) values.put("_revision", row.text("revision"));
            Path image = pending[0]; String reference = imageReference[0];
            dialog.getDialogPane().setDisable(true);
            app.tasks().submit(() -> {
                Path imported = null;
                try {
                    String stored = image == null ? reference : ImageStorage.importImage(image, app.store.directory);
                    if (image != null) imported = app.store.directory.resolve(stored);
                    values.put("image", stored); return app.store.save(entity, row == null ? null : row.number(entity.id), values);
                } catch (Exception ex) { if (imported != null) Files.deleteIfExists(imported); throw ex; }
            }, id -> { dialog.getDialogPane().setDisable(false); dialog.close(); saved.accept(id); }, ex -> {
                dialog.getDialogPane().setDisable(false); error.setText(app.message(ex));
                if (app.store.user() == null) { dialog.close(); app.showLogin(); }
            });
        });
        return dialog;
    }
}
