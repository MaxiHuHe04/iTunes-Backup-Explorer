package me.maxih.itunes_backup_explorer.ui;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.api.*;
import me.maxih.itunes_backup_explorer.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.stream.Collectors;

public class FileSearchTabController {
    private static final Logger logger = LoggerFactory.getLogger(FileSearchTabController.class);

    ITunesBackup selectedBackup;

    @FXML ComboBox<String> domainComboBox;
    @FXML ComboBox<String> fileTypeComboBox;
    @FXML TextField relativePathQueryField;
    @FXML TableView<BackupFileEntry> filesTable;
    @FXML Label searchResultsCount;
    @FXML Label searchHint;

    @FXML
    public void initialize() {
        setupFileTypeComboBox();

        TableColumn<BackupFileEntry, String> domainColumn = new TableColumn<>("Domain");
        TableColumn<BackupFileEntry, String> nameColumn = new TableColumn<>("Name");
        TableColumn<BackupFileEntry, String> typeColumn = new TableColumn<>("Type");
        TableColumn<BackupFileEntry, String> pathColumn = new TableColumn<>("Path");
        TableColumn<BackupFileEntry, String> sizeColumn = new TableColumn<>("Size");

        domainColumn.setCellValueFactory(new PropertyValueFactory<>("domain"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        pathColumn.setCellValueFactory(new PropertyValueFactory<>("parentPath"));

        typeColumn.setCellValueFactory(cellData -> {
            String fileName = cellData.getValue().getFileName();
            int dotIndex = fileName.lastIndexOf('.');
            String extension = dotIndex > 0 ? fileName.substring(dotIndex + 1).toUpperCase() : "";
            return new javafx.beans.property.SimpleStringProperty(extension);
        });

        sizeColumn.setCellValueFactory(cellData -> {
            long size = cellData.getValue().getSize();
            return new javafx.beans.property.SimpleStringProperty(FileSize.format(size));
        });

        domainColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.19));
        nameColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.24));
        typeColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.08));
        pathColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.34));
        sizeColumn.prefWidthProperty().bind(this.filesTable.widthProperty().multiply(0.12));

        this.filesTable.getColumns().addAll(Arrays.asList(domainColumn, nameColumn, typeColumn, pathColumn, sizeColumn));

        this.filesTable.setRowFactory(tableView -> {
            TableRow<BackupFileEntry> row = new TableRow<>();

            row.itemProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == null || newValue.getFile().isEmpty()) return;
                row.setContextMenu(FileActions.getContextMenu(
                        newValue.getFile().get(),
                        tableView.getScene().getWindow(),
                        removedIDs -> filesTable.getItems().removeIf(entry ->
                                entry.getFile().map(f -> removedIDs.contains(f.fileID)).orElse(false)
                        ))
                );
            });

            return row;
        });

        domainComboBox.setOnAction(event -> searchFiles());
        relativePathQueryField.setOnAction(event -> searchFiles());
        fileTypeComboBox.setOnAction(event -> searchFiles());
    }

    private void setupFileTypeComboBox() {
        fileTypeComboBox.setItems(FXCollections.observableArrayList(
            "All Types",
            "Images (jpg, png, heic)",
            "Videos (mov, mp4)",
            "Databases (db, sqlite)",
            "Plists (plist)",
            "Text (txt, csv, json)"
        ));
        fileTypeComboBox.setValue("All Types");
    }

    @FXML
    public void searchFiles() {
        if (this.selectedBackup == null) {
            logger.warn("No backup selected for search");
            return;
        }

        filesTable.getItems().clear();

        String domainQuery = getDomainQuery();
        String pathQuery = relativePathQueryField.getText().trim();
        if (pathQuery.isEmpty()) {
            pathQuery = "%";
        } else if (!pathQuery.contains("%")) {
            pathQuery = "%" + pathQuery + "%";
        }

        boolean hasSearchTerm = !pathQuery.equals("%");
        searchHint.setVisible(hasSearchTerm);
        searchHint.setManaged(hasSearchTerm);

        String selectedFileType = fileTypeComboBox.getValue();
        ITunesBackup backup = this.selectedBackup;
        String finalPathQuery = pathQuery;

        javafx.concurrent.Task<List<BackupFile>> task = new javafx.concurrent.Task<>() {
            @Override
            protected List<BackupFile> call() throws Exception {
                List<BackupFile> searchResult = finalPathQuery.equals("%")
                        ? backup.searchFiles(domainQuery, "%")
                        : backup.searchFilesFullText(domainQuery, finalPathQuery);

                if (selectedFileType != null && !selectedFileType.equals("All Types")) {
                    searchResult = filterByFileType(searchResult, selectedFileType);
                }

                if (!PreferencesController.getSearchIncludeNonFiles()) {
                    searchResult = searchResult.stream()
                            .filter(file -> file.getFileType() == BackupFile.FileType.FILE)
                            .collect(Collectors.toList());
                }

                return searchResult;
            }
        };

        task.setOnSucceeded(event -> {
            List<BackupFile> searchResult = task.getValue();
            int totalResults = searchResult.size();
            int resultLimit = PreferencesController.getSearchResultLimit();
            boolean limited = resultLimit > 0 && totalResults > resultLimit;
            if (limited) {
                searchResult = searchResult.subList(0, resultLimit);
            }

            filesTable.setItems(FXCollections.observableList(searchResult.stream().map(BackupFileEntry::new).collect(Collectors.toList())));
            updateResultsCount(searchResult.size(), totalResults);
        });

        task.setOnFailed(event -> {
            logger.error("Failed to search files", task.getException());
            Dialogs.showAlert(Alert.AlertType.ERROR, task.getException().getMessage());
        });

        new Thread(task).start();
    }

    private String getDomainQuery() {
        String domain = domainComboBox.getEditor().getText();
        if (domain == null || domain.isEmpty() || domain.equals("All Domains")) {
            return "%";
        }
        return domain;
    }

    private List<BackupFile> filterByFileType(List<BackupFile> files, String fileType) {
        Set<String> extensions = new HashSet<>();

        switch (fileType) {
            case "Images (jpg, png, heic)":
                extensions.addAll(Arrays.asList("jpg", "jpeg", "png", "heic", "heif"));
                break;
            case "Videos (mov, mp4)":
                extensions.addAll(Arrays.asList("mov", "mp4", "m4v"));
                break;
            case "Databases (db, sqlite)":
                extensions.addAll(Arrays.asList("db", "sqlite", "sqlite3"));
                break;
            case "Plists (plist)":
                extensions.add("plist");
                break;
            case "Text (txt, csv, json)":
                extensions.addAll(Arrays.asList("txt", "csv", "json", "log"));
                break;
        }

        return files.stream()
            .filter(file -> {
                String fileName = file.getFileName().toLowerCase();
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                    String ext = fileName.substring(dotIndex + 1);
                    return extensions.contains(ext);
                }
                return false;
            })
            .collect(Collectors.toList());
    }

    private void updateResultsCount(int shownCount, int totalCount) {
        if (searchResultsCount != null) {
            if (shownCount != totalCount) {
                searchResultsCount.setText(shownCount + "/" + totalCount + " results (limited)");
            } else {
                searchResultsCount.setText(shownCount + " result" + (shownCount != 1 ? "s" : ""));
            }
        }
    }

    @FXML
    public void exportMatching() {
        if (this.filesTable.getItems().size() == 0) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File lastDirectory = PreferencesController.getLastExportDirectory();
        if (lastDirectory != null) chooser.setInitialDirectory(lastDirectory);
        File destination = chooser.showDialog(this.filesTable.getScene().getWindow());

        if (destination == null || !destination.exists()) return;
        PreferencesController.setLastExportDirectory(destination);

        boolean withRelativePath = PreferencesController.getCreateDirectoryStructure();
        boolean preserveTimestamps = PreferencesController.getPreserveTimestamps();
        boolean skipExisting = PreferencesController.getSkipExistingFiles();
        this.filesTable.getItems().forEach(backupFile -> {
            if (backupFile.getFile().isEmpty()) return;
            try {
                backupFile.getFile().get().extractToFolder(destination, withRelativePath, preserveTimestamps);
            } catch (FileAlreadyExistsException e) {
                if (!skipExisting) Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
                logger.error("Failed to export matching file", e);
                Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
            }
        });
    }

    @FXML
    public void quickFilterAllFiles() {
        setQuickFilter("%", "%");
    }

    @FXML
    public void quickFilterPhotos() {
        performMultiPatternSearch("CameraRollDomain", Arrays.asList("%.HEIC", "%.JPG", "%.JPEG", "%.PNG"));
    }

    @FXML
    public void quickFilterVideos() {
        performMultiPatternSearch("CameraRollDomain", Arrays.asList("%.MOV", "%.MP4", "%.M4V"));
    }

    @FXML
    public void quickFilterWhatsApp() {
        setQuickFilter("AppDomainGroup-group.net.whatsapp.WhatsApp.shared", "%");
    }

    @FXML
    public void quickFilterContacts() {
        setQuickFilter("HomeDomain", "%AddressBook%");
    }

    @FXML
    public void quickFilterMessages() {
        setQuickFilter("HomeDomain", "%sms%");
    }

    @FXML
    public void quickFilterNotes() {
        setQuickFilter("AppDomainGroup-group.com.apple.notes", "%");
    }

    @FXML
    public void quickFilterVoiceMemos() {
        setQuickFilter("MediaDomain", "%Recording%");
    }

    private void setQuickFilter(String domain, String path) {
        domainComboBox.setValue(domain);
        relativePathQueryField.setText(path.equals("%") ? "" : path);
        fileTypeComboBox.setValue("All Types");
        searchFiles();
    }

    private void performMultiPatternSearch(String domain, List<String> pathPatterns) {
        filesTable.getItems().clear();

        ITunesBackup backup = this.selectedBackup;
        javafx.concurrent.Task<List<BackupFile>> task = new javafx.concurrent.Task<>() {
            @Override
            protected List<BackupFile> call() throws Exception {
                List<BackupFile> results = backup.searchFilesMultiPattern(domain, pathPatterns);

                if (!PreferencesController.getSearchIncludeNonFiles()) {
                    results = results.stream()
                            .filter(file -> file.getFileType() == BackupFile.FileType.FILE)
                            .collect(Collectors.toList());
                }

                return results;
            }
        };

        task.setOnSucceeded(event -> {
            List<BackupFile> results = task.getValue();
            int totalResults = results.size();
            int resultLimit = PreferencesController.getSearchResultLimit();
            if (resultLimit > 0 && results.size() > resultLimit) {
                results = results.subList(0, resultLimit);
            }

            filesTable.setItems(FXCollections.observableList(results.stream().map(BackupFileEntry::new).collect(Collectors.toList())));
            updateResultsCount(results.size(), totalResults);

            domainComboBox.setValue(domain);
            relativePathQueryField.setText("");
            fileTypeComboBox.setValue("All Types");
        });

        task.setOnFailed(event -> {
            logger.error("Failed to search files", task.getException());
            Dialogs.showAlert(Alert.AlertType.ERROR, task.getException().getMessage());
        });

        new Thread(task).start();
    }

    public void tabShown(ITunesBackup backup) {
        if (backup == this.selectedBackup && this.filesTable.getItems() != null) return;

        this.filesTable.setItems(FXCollections.observableArrayList());
        this.selectedBackup = backup;
        updateResultsCount(0, 0);

        populateDomainComboBox();
    }

    private void populateDomainComboBox() {
        try {
            List<BackupFile> domainRoots = selectedBackup.queryDomainRoots();

            List<String> domains = domainRoots.stream()
                .map(file -> file.domain)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

            domains.add(0, "All Domains");

            domainComboBox.setItems(FXCollections.observableArrayList(domains));
            domainComboBox.setValue("All Domains");
        } catch (DatabaseConnectionException e) {
            logger.error("Failed to load domains", e);
            domainComboBox.setItems(FXCollections.observableArrayList("All Domains"));
            domainComboBox.setValue("All Domains");
        }
    }
}
