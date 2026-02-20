package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class AppsTabController {
    private static final Logger logger = LoggerFactory.getLogger(AppsTabController.class);
    private ITunesBackup selectedBackup;
    private Map<String, String> appDomains = new HashMap<>();

    @FXML
    ListView<String> appListView;

    @FXML
    TreeView<BackupFileEntry> appFilesTree;

    @FXML
    Label appNameLabel;

    @FXML
    Label bundleIdLabel;

    @FXML
    Label versionLabel;

    @FXML
    Button exportAppButton;

    @FXML
    public void initialize() {
        appListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                loadAppDetails(newValue);
            }
        });

        exportAppButton.setDisable(true);

        appFilesTree.setCellFactory(view -> new TreeCell<>() {
            {
                itemProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue == null || newValue.getFile().isEmpty()) return;
                    TreeItem<BackupFileEntry> parent = getTreeItem().getParent();
                    setContextMenu(FileActions.getContextMenu(
                            newValue.getFile().get(),
                            appFilesTree.getScene().getWindow(),
                            removedIDs -> parent.getChildren().remove(getTreeItem()))
                    );
                });
            }

            @Override
            protected void updateItem(BackupFileEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getDisplayName());
                }
            }
        });
    }

    public void tabShown(ITunesBackup backup) {
        if (backup == this.selectedBackup && !this.appListView.getItems().isEmpty()) return;

        this.selectedBackup = backup;
        this.appListView.getItems().clear();
        this.appDomains.clear();
        this.appFilesTree.setRoot(null);
        this.appNameLabel.setText("--");
        this.bundleIdLabel.setText("--");
        this.versionLabel.setText("--");
        this.exportAppButton.setDisable(true);

        if (backup.isLocked()) {
            logger.warn("Cannot load apps: backup is locked");
            return;
        }

        try {
            List<BackupFile> domains = backup.queryDomainRoots();

            for (BackupFile domain : domains) {
                String domainName = domain.domain;
                if (domainName.startsWith("AppDomain-")) {
                    String bundleId = domainName.substring("AppDomain-".length());
                    String appName = extractAppName(bundleId);
                    appDomains.put(appName, domainName);
                    appListView.getItems().add(appName);
                }
            }

            appListView.getItems().sort(String::compareTo);

        } catch (DatabaseConnectionException e) {
            logger.error("Failed to query app domains", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
        }
    }

    private String extractAppName(String bundleId) {
        String[] parts = bundleId.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
        }
        return bundleId;
    }

    private void loadAppDetails(String appName) {
        String domain = appDomains.get(appName);
        if (domain == null) return;

        String bundleId = domain.substring("AppDomain-".length());
        appNameLabel.setText(appName);
        bundleIdLabel.setText(bundleId);
        versionLabel.setText("--");

        appFilesTree.setCursor(Cursor.WAIT);
        appListView.setCursor(Cursor.WAIT);

        Task<TreeItem<BackupFileEntry>> loadTask = new Task<>() {
            @Override
            protected TreeItem<BackupFileEntry> call() throws Exception {
                List<BackupFile> files = selectedBackup.queryDomainFiles(false, domain);

                TreeItem<BackupFileEntry> root = new TreeItem<>(new BackupFileEntry(appName));

                Map<String, TreeItem<BackupFileEntry>> directoryMap = new HashMap<>();
                directoryMap.put("", root);

                List<BackupFile> sortedFiles = files.stream()
                        .sorted(Comparator.comparing(f -> f.relativePath))
                        .collect(Collectors.toList());

                for (BackupFile file : sortedFiles) {
                    String path = file.relativePath;
                    String parentPath = getParentPath(path);
                    String fileName = getFileName(path);

                    TreeItem<BackupFileEntry> parentItem = directoryMap.get(parentPath);
                    if (parentItem == null) {
                        parentItem = createMissingParents(parentPath, directoryMap, root);
                    }

                    TreeItem<BackupFileEntry> fileItem = new TreeItem<>(new BackupFileEntry(file));
                    parentItem.getChildren().add(fileItem);

                    if (file.getFileType() == BackupFile.FileType.DIRECTORY) {
                        directoryMap.put(path, fileItem);
                    }
                }

                return root;
            }
        };

        loadTask.setOnSucceeded(event -> {
            appFilesTree.setRoot(loadTask.getValue());
            appFilesTree.setCursor(Cursor.DEFAULT);
            appListView.setCursor(Cursor.DEFAULT);
            exportAppButton.setDisable(false);
        });

        loadTask.setOnFailed(event -> {
            appFilesTree.setCursor(Cursor.DEFAULT);
            appListView.setCursor(Cursor.DEFAULT);
            Throwable exception = loadTask.getException();
            if (exception != null) {
                logger.error("Failed to load app files", exception);
                Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to load app files: " + exception.getMessage());
            }
        });

        new Thread(loadTask).start();
    }

    private TreeItem<BackupFileEntry> createMissingParents(String path, Map<String, TreeItem<BackupFileEntry>> directoryMap, TreeItem<BackupFileEntry> root) {
        String[] parts = path.split("/");
        StringBuilder currentPath = new StringBuilder();

        TreeItem<BackupFileEntry> currentParent = root;

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) currentPath.append("/");
            currentPath.append(parts[i]);

            String pathStr = currentPath.toString();
            if (!directoryMap.containsKey(pathStr)) {
                TreeItem<BackupFileEntry> dirItem = new TreeItem<>(new BackupFileEntry(parts[i]));
                currentParent.getChildren().add(dirItem);
                directoryMap.put(pathStr, dirItem);
                currentParent = dirItem;
            } else {
                currentParent = directoryMap.get(pathStr);
            }
        }

        return currentParent;
    }

    private String getParentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "";
        return path.substring(0, lastSlash);
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1);
    }

    @FXML
    public void exportAppData() {
        String selectedApp = appListView.getSelectionModel().getSelectedItem();
        if (selectedApp == null) return;

        String domain = appDomains.get(selectedApp);
        if (domain == null) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select destination directory");
        File lastDirectory = PreferencesController.getLastExportDirectory();
        if (lastDirectory != null) chooser.setInitialDirectory(lastDirectory);
        File destination = chooser.showDialog(appFilesTree.getScene().getWindow());
        if (destination == null) return;
        PreferencesController.setLastExportDirectory(destination);

        Task<Void> exportTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<BackupFile> files = selectedBackup.queryDomainFiles(false, domain);
                ButtonType skipButtonType = new ButtonType("Skip", ButtonBar.ButtonData.NEXT_FORWARD);
                ButtonType skipAllExistingButtonType = new ButtonType("Skip all existing", ButtonBar.ButtonData.NEXT_FORWARD);
                boolean skipExisting = PreferencesController.getSkipExistingFiles();
                boolean withRelativePath = PreferencesController.getCreateDirectoryStructure();
                boolean preserveTimestamps = PreferencesController.getPreserveTimestamps();

                for (int i = 0; i < files.size(); i++) {
                    if (isCancelled()) break;

                    try {
                        BackupFile file = files.get(i);
                        updateMessage("Exporting file " + (i + 1) + " of " + files.size() + ": " + file.relativePath);

                        file.extractToFolder(destination, withRelativePath, preserveTimestamps);
                        updateProgress(i + 1, files.size());
                    } catch (ClosedByInterruptException e) {
                        break;
                    } catch (FileAlreadyExistsException e) {
                        if (skipExisting) continue;
                        String file = e.getFile();
                        if (file == null) file = "";

                        Optional<ButtonType> response = showFileExportError(
                                "File already exists:\n" + file, skipButtonType, skipAllExistingButtonType, ButtonType.CANCEL);
                        if (response.isEmpty() || response.get() == ButtonType.CANCEL) break;
                        if (response.get() == skipAllExistingButtonType) skipExisting = true;
                    } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
                        logger.error("Failed to export app file", e);
                        Optional<ButtonType> response = showFileExportError(
                                e.getMessage() + "\nContinue?", ButtonType.YES, ButtonType.CANCEL);
                        if (response.isEmpty() || response.get() == ButtonType.CANCEL) break;
                    }
                }

                return null;
            }
        };

        Dialogs.ProgressAlert progress = new Dialogs.ProgressAlert("Exporting " + selectedApp + "...", exportTask, true);
        new Thread(exportTask).start();
        progress.showAndWait();
    }

    private Optional<ButtonType> showFileExportError(String msg, ButtonType... buttonTypes) throws ExecutionException, InterruptedException {
        Task<Optional<ButtonType>> alertTask = new Task<>() {
            @Override
            protected Optional<ButtonType> call() {
                return new Alert(Alert.AlertType.ERROR, msg, buttonTypes).showAndWait();
            }
        };

        Platform.runLater(alertTask);
        return alertTask.get();
    }
}
