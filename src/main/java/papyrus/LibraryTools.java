package papyrus;

import javafx.geometry.*;
import javafx.print.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** User-facing backup, archive, transfer, account, and report workflows. */
final class LibraryTools {
    private final PapyrusApp app;
    LibraryTools(PapyrusApp app) { this.app = app; }

    private File choose(boolean save, String title, String suffix, String name) {
        FileChooser chooser = new FileChooser(); chooser.setTitle(app.t(title)); chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(suffix.toUpperCase(Locale.ROOT), "*." + suffix));
        return save ? chooser.showSaveDialog(app.window()) : chooser.showOpenDialog(app.window());
    }
    void backup() {
        File file = choose(true, "backup", "zip", "Papyrus-backup-" + LocalDate.now() + ".zip");
        if (file != null) work("backup", () -> { BackupArchive.create(app.store, file.toPath()); return file; }, result -> app.inform("backupSaved", result.toString()));
    }
    void restoreBackup() {
        File file = choose(false, "restoreBackup", "zip", "");
        if (file == null || !app.confirm("restoreBackupConfirm")) return;
        work("restoreBackup", () -> BackupArchive.restore(app.store, file.toPath()), recovery -> {
            app.showLogin(); app.inform("backupRestored", app.t("recoveryBackup") + "\n" + recovery);
        });
    }
    void password() {
        Dialog<ButtonType> dialog = app.dialog(app.t("changePassword"), true, "settings");
        PasswordField current = new PasswordField(), replacement = new PasswordField(), confirm = new PasswordField();
        current.setId("current-password"); replacement.setId("new-password"); confirm.setId("confirm-new-password");
        Label error = app.label("", "error-label"); error.setWrapText(true);
        VBox form = new VBox(18, app.formField("currentPassword", current, true), app.formField("newPassword", replacement, true), app.formField("confirmPassword", confirm, true), error); form.setPrefWidth(420); dialog.getDialogPane().setContent(form);
        Button save = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
        save.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); if (!replacement.getText().equals(confirm.getText())) { error.setText(app.t("passwordMismatch")); return; }
            String oldValue = current.getText(), newValue = replacement.getText(); dialog.getDialogPane().setDisable(true);
            app.tasks().submit(() -> { app.store.changePassword(oldValue, newValue); return true; }, result -> { dialog.getDialogPane().setDisable(false); dialog.close(); app.inform("passwordChanged", ""); }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(app.message(ex)); });
        });
        dialog.showAndWait();
    }
    void archived(Store.Entity entity) {
        Dialog<ButtonType> dialog = app.dialog(app.t("archived") + " · " + app.t(entity.name().toLowerCase(Locale.ROOT)), false, entity.name().toLowerCase(Locale.ROOT));
        TableView<Store.Row> table = app.table(); table.setId("archived-records"); app.column(table, "id", entity.id, 80);
        app.column(table, "name", row -> switch (entity) { case BOOKS -> row.text("doc_libelle"); case STUDENTS, STAFF -> row.text("prenom") + " " + row.text("nom"); case TYPES -> row.text("ty_libelle"); case CATEGORIES -> row.text("cat_libelle"); }, 400);
        Label error = app.label("", "error-label"); error.setWrapText(true);
        Button restore = app.button("restore", () -> {
            Store.Row selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            dialog.getDialogPane().setDisable(true);
            app.tasks().submit(() -> { app.store.restore(entity, selected.number(entity.id)); return app.store.list(entity, true); }, rows -> {
                table.getItems().setAll(rows); dialog.getDialogPane().setDisable(false); app.refresh();
            }, ex -> { dialog.getDialogPane().setDisable(false); error.setText(app.message(ex)); });
        });
        restore.setId("restore-record"); restore.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        VBox body = new VBox(16, app.label(app.t("archiveHint"), "muted"), table, restore, error); body.setPrefWidth(580); dialog.getDialogPane().setContent(body);
        app.tasks().submit(() -> app.store.list(entity, true), rows -> table.getItems().setAll(rows), ex -> error.setText(app.message(ex)));
        dialog.showAndWait();
    }
    HBox transferButtons(Store.Entity entity) {
        Button template = app.button("csvTemplate", () -> { File file = choose(true, "csvTemplate", "csv", entity.name().toLowerCase(Locale.ROOT) + "-template.csv"); if (file != null) work("csvTemplate", () -> { CsvTransfer.template(file.toPath(), entity); return file; }, result -> app.inform("fileSaved", result.toString())); });
        return new HBox(8, app.button("importCsv", () -> importCsv(entity)), app.button("exportCsv", () -> exportCsv(entity)), template);
    }
    private void exportCsv(Store.Entity entity) {
        File file = choose(true, "exportCsv", "csv", entity.name().toLowerCase(Locale.ROOT) + ".csv");
        if (file != null) work("exportCsv", () -> { CsvTransfer.export(file.toPath(), entity, app.store.list(entity)); return file; }, result -> app.inform("fileSaved", result.toString()));
    }
    private void importCsv(Store.Entity entity) {
        File file = choose(false, "importCsv", "csv", ""); if (file == null) return;
        work("importCsv", () -> CsvTransfer.read(file.toPath(), entity), records -> {
            Dialog<ButtonType> preview = app.dialog(app.t("importCsv"), true, entity.name().toLowerCase(Locale.ROOT));
            Label explanation = app.label(app.t("csvImportHint"), "muted"); explanation.setWrapText(true);
            TextArea sample = new TextArea(String.join("\n", records.stream().limit(8).map(row -> String.join(" · ", row.values())).toList())); sample.setEditable(false); sample.setWrapText(true); sample.setPrefRowCount(8);
            Label error = app.label("", "error-label"); error.setWrapText(true);
            VBox content = new VBox(16, app.label(records.size() + " " + app.t("recordCount"), "section-title"), explanation, sample, error); content.setPrefWidth(580); preview.getDialogPane().setContent(content);
            Button save = (Button) preview.getDialogPane().lookupButton(preview.getDialogPane().getButtonTypes().getFirst()); save.setDisable(records.isEmpty());
            save.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume(); preview.getDialogPane().setDisable(true);
                app.tasks().submit(() -> app.store.importRecords(entity, records), count -> { preview.getDialogPane().setDisable(false); preview.close(); app.refresh(); app.inform("importComplete", count + " " + app.t("recordCount")); }, ex -> { preview.getDialogPane().setDisable(false); error.setText(app.message(ex)); });
            });
            preview.showAndWait();
        });
    }
    void exportLoans(List<Store.Row> visible) {
        List<Store.Row> snapshot = List.copyOf(visible); File file = choose(true, "exportVisibleCsv", "csv", "loans.csv");
        if (file != null) work("exportVisibleCsv", () -> { app.store.validateSession(); CsvTransfer.exportLoans(file.toPath(), snapshot); return file; }, result -> app.inform("fileSaved", result.toString()));
    }
    void printOverdue() {
        work("printOverdue", () -> app.store.loans(false, null, null).stream().filter(row -> row.text("return_d").compareTo(LocalDate.now().toString()) < 0).toList(), rows -> {
            if (rows.isEmpty()) { app.inform("noResults", ""); return; }
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) { app.inform("noPrinter", ""); return; }
            if (!job.showPrintDialog(app.window())) return;
            PageLayout layout = job.getPrinter().createPageLayout(Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.DEFAULT);
            job.getJobSettings().setPageLayout(layout); job.getJobSettings().setJobName("Papyrus - " + app.t("overdue"));
            boolean success = true;
            int perPage = Math.max(1, (int) ((layout.getPrintableHeight() - 70) / 65));
            for (int offset = 0; offset < rows.size() && success; offset += perPage) {
                VBox page = reportPage(rows.subList(offset, Math.min(rows.size(), offset + perPage)), offset / perPage + 1, (rows.size() + perPage - 1) / perPage, layout.getPrintableWidth());
                success = job.printPage(layout, page);
            }
            if (success) job.endJob(); else { job.cancelJob(); app.inform("printFailed", ""); }
        });
    }
    VBox reportPage(List<Store.Row> rows, int pageNumber, int pageCount, double width) {
        VBox page = new VBox(12); page.setPrefWidth(width); page.setNodeOrientation(app.i18n().rtl() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        page.getChildren().addAll(new Label("Papyrus · " + app.t("overdue")), new Label(LocalDate.now() + " · " + pageNumber + "/" + pageCount));
        for (Store.Row row : rows) {
            Label title = new Label(shorten(row.text("doc_libelle")) + " · " + row.text("copy_code")); title.setStyle("-fx-font-weight: bold;"); title.setWrapText(false); title.setMaxWidth(width);
            Label details = new Label(shorten(row.text("student")) + " · " + row.text("cne") + " · " + row.text("phone") + "\n" + app.t("dueDate") + ": " + row.text("return_d")); details.setWrapText(true); details.setMaxWidth(width);
            VBox entry = new VBox(3, title, details, new Separator()); entry.setMinHeight(53); entry.setPrefHeight(53); entry.setMaxHeight(53); details.setMaxHeight(28); page.getChildren().add(entry);
        }
        new Scene(page); page.setStyle("-fx-font-size: 10px; -fx-background-color: white; -fx-text-fill: black;"); page.applyCss(); page.layout(); return page;
    }
    private static String shorten(String text) { return text.length() <= 70 ? text : text.substring(0, 67) + "…"; }

    <T> void work(String title, Callable<T> operation, Consumer<T> finished) {
        Dialog<ButtonType> progress = app.dialog(app.t(title), false, "settings");
        progress.getDialogPane().setContent(new HBox(16, new ProgressIndicator(), new Label(app.t("working"))));
        progress.getDialogPane().lookupButton(progress.getDialogPane().getButtonTypes().getFirst()).setDisable(true);
        progress.setOnCloseRequest(e -> e.consume()); progress.show();
        app.tasks().submit(operation, value -> { progress.setOnCloseRequest(null); progress.close(); finished.accept(value); }, ex -> { progress.setOnCloseRequest(null); progress.close(); app.showError(ex); });
    }
}
