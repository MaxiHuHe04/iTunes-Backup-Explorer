package me.maxih.itunes_backup_explorer.ui;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.api.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class FileSearchTabController {

    ITunesBackup selectedBackup;

    @FXML
    TextField domainQueryField;
    @FXML
    TextField relativePathQueryField;
    @FXML
    TableView<BackupFileEntry> filesTable;

    @FXML
    public void initialize() {
        TableColumn<BackupFileEntry, String> domainColumn = new TableColumn<>("Domain");
        TableColumn<BackupFileEntry, String> nameColumn = new TableColumn<>("Name");
        TableColumn<BackupFileEntry, String> pathColumn = new TableColumn<>("Path");
        TableColumn<BackupFileEntry, Number> sizeColumn = new TableColumn<>("Size");

        domainColumn.setCellValueFactory(new PropertyValueFactory<>("domain"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        pathColumn.setCellValueFactory(new PropertyValueFactory<>("parentPath"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));

        domainColumn.prefWidthProperty().bind(filesTable.widthProperty().multiply(0.2));
        nameColumn.prefWidthProperty().bind(filesTable.widthProperty().multiply(0.3));
        pathColumn.prefWidthProperty().bind(filesTable.widthProperty().multiply(0.4));
        sizeColumn.prefWidthProperty().bind(filesTable.widthProperty().multiply(0.1));

        filesTable.getColumns().addAll(Arrays.asList(domainColumn, nameColumn, pathColumn, sizeColumn));

        filesTable.setRowFactory(tableView -> {
            TableRow<BackupFileEntry> row = new TableRow<>();
            row.itemProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == null || newValue.getFile().isEmpty()) return;
                row.setContextMenu(FileActions.getContextMenu(
                    newValue.getFile().get(),
                    tableView.getScene().getWindow(),
                    removedIDs -> filesTable.getItems().removeIf(entry ->
                        entry.getFile().map(f -> removedIDs.contains(f.fileID)).orElse(false)
                    )
                ));
            });
            return row;
        });
    }

    @FXML
    public void searchFiles() {
        try {
            List<BackupFile> searchResult = selectedBackup.searchFiles(
                domainQueryField.getText(),
                relativePathQueryField.getText()
            );

            filesTable.setItems(FXCollections.observableList(
                searchResult.stream()
                    .map(BackupFileEntry::new)
                    .collect(Collectors.toList())
            ));
        } catch (DatabaseConnectionException e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
            });
        }
    }

    @FXML
    public void exportMatching() {
        if (filesTable.getItems().isEmpty()) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File destination = chooser.showDialog(filesTable.getScene().getWindow());
        if (destination == null || !destination.exists()) return;

        // Create export task for progress bar
        Task<Integer> exportTask = new Task<Integer>() {
            @Override
            protected Integer call() throws Exception {
                List<BackupFileEntry> items = filesTable.getItems();
                int successCount = 0;
                int totalCount = items.size();

                for (int i = 0; i < totalCount; i++) {
                    if (Thread.interrupted()) break; // for cancel

                    BackupFileEntry backupFile = items.get(i);
                    if (backupFile.getFile().isEmpty()) continue;

                    try {
                        backupFile.getFile().get().extractToFolder(destination, true);
                        successCount++;
                    } catch (IOException | BackupReadException
                             | NotUnlockedException | UnsupportedCryptoException e) {
                        e.printStackTrace();
                    }

                    // update progress
                    updateProgress(i + 1, totalCount);
                    updateMessage("Exporting " + (i + 1) + " of " + totalCount + " files...");
                }

                return successCount;
            }
        };

        exportTask.setOnSucceeded(event -> {
            Platform.runLater(() -> {
                Integer successCount = exportTask.getValue();
                int totalCount = filesTable.getItems().size();
                String message = String.format(
                    "%d of %d files successfully exported to:%n%s",
                    successCount, totalCount, destination.getAbsolutePath()
                );
                Dialogs.showSuccessDialog(message);
            });
        });

        exportTask.setOnFailed(event -> {
            Throwable exception = exportTask.getException();
            if (exception != null) {
                exception.printStackTrace();
                Platform.runLater(() -> {
                    Dialogs.showAlert(
                        Alert.AlertType.ERROR,
                        "Export error: " + exception.getMessage(),
                        ButtonType.OK
                    );
                });
            }
        });

        Dialogs.ProgressAlert progressAlert = new Dialogs.ProgressAlert("Exporting files...", exportTask, true);

        // I *think* this is required because we have a progress bar now?
        Thread exportThread = new Thread(exportTask);
        exportThread.setDaemon(true);
        exportThread.start();

        progressAlert.showAndWait();
    }

    public void tabShown(ITunesBackup backup) {
        if (backup == selectedBackup) return;
            filesTable.setItems(null);
            selectedBackup = backup;
    }
}