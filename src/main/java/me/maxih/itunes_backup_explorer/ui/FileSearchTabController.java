package me.maxih.itunes_backup_explorer.ui;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
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
        domainColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.2));
        nameColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.3));
        pathColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.4));
        sizeColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.1));

        this.filesTable.getColumns().addAll(Arrays.asList(domainColumn, nameColumn, pathColumn, sizeColumn));

        this.filesTable.setRowFactory(tableView -> {
            TableRow<BackupFileEntry> row = new TableRow<>();

            row.itemProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == null || newValue.getFile().isEmpty()) return;
                row.setContextMenu(FileActions.getContextMenu(
                        newValue.getFile().get(),
                        tableView.getScene().getWindow(),
                        () -> filesTable.getItems().remove(newValue))
                );
            });

            return row;
        });
    }

    @FXML
    public void searchFiles() {
        try {
            List<BackupFile> searchResult = selectedBackup.searchFiles(domainQueryField.getText(), relativePathQueryField.getText());

            this.filesTable.setItems(FXCollections.observableList(searchResult.stream().map(BackupFileEntry::new).collect(Collectors.toList())));
        } catch (DatabaseConnectionException e) {
            e.printStackTrace();
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
        }
    }

    @FXML
    public void exportMatching() {
        if (this.filesTable.getItems().size() == 0) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File destination = chooser.showDialog(this.filesTable.getScene().getWindow());

        if (destination == null || !destination.exists()) return;

        this.filesTable.getItems().forEach(backupFile -> {
            if (backupFile.getFile().isEmpty()) return;
            try {
                backupFile.getFile().get().extractToFolder(destination, true);
            } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
                e.printStackTrace();
                Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            }
        });
    }

    public void tabShown(ITunesBackup backup) {
        if (backup == this.selectedBackup) return;

        this.filesTable.setItems(null);
        this.selectedBackup = backup;
    }
}
