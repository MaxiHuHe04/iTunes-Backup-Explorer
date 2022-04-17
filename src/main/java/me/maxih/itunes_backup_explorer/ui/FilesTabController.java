package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;
import me.maxih.itunes_backup_explorer.api.*;
import me.maxih.itunes_backup_explorer.util.BackupPathUtils;
import me.maxih.itunes_backup_explorer.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FilesTabController {

    private static final Image domainGroupIcon = ITunesBackupExplorer.getIcon("domain_group.png");
    private static final Image folderIcon = ITunesBackupExplorer.getIcon("folder.png");
    private static final Image fileIcon = ITunesBackupExplorer.getIcon("file.png");


    private ITunesBackup selectedBackup;

    Task<TreeItem<BackupFileEntry>> loadDomainFilesTask;

    @FXML
    SplitPane splitPane;

    @FXML
    TreeView<BackupFileEntry> domainsTreeView;

    @FXML
    TreeView<BackupFileEntry> filesTreeView;

    @FXML
    public void initialize() {
        this.domainsTreeView.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(BackupFileEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    this.setPrefHeight(36);


                    CheckBox checkBox = new CheckBox();
                    checkBox.selectedProperty().bindBidirectional(item.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().bindBidirectional(item.checkBoxIndeterminateProperty());

                    HBox graphic = new HBox(8, checkBox);
                    graphic.setAlignment(Pos.CENTER_LEFT);

                    ImageView icon = new ImageView(item.getFile().isPresent() ? folderIcon : domainGroupIcon);
                    icon.setFitWidth(32);
                    icon.setFitHeight(32);
                    graphic.getChildren().add(icon);

                    this.getChildren().add(new Label(String.valueOf(item.getSize())));

                    setGraphic(graphic);
                    setText(item.getDisplayName());

                    getDisclosureNode().setTranslateY(8);
                }

            }
        });

        domainsTreeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.getValue() == null || newValue.getValue().getFile().isEmpty()) return;
            if (loadDomainFilesTask != null) loadDomainFilesTask.cancel(true);

            domainsTreeView.setCursor(Cursor.WAIT);
            filesTreeView.setCursor(Cursor.WAIT);

            BackupFile file = newValue.getValue().getFile().get();
            loadDomainFilesTask = new Task<>() {
                @Override
                protected TreeItem<BackupFileEntry> call() {
                    TreeItem<BackupFileEntry> root = new TreeItem<>(new BackupFileEntry(file));
                    List<BackupFile> result = selectedBackup.queryDomainFiles(false, file.domain);
                    try {
                        insertAsTree(root, result.stream().map(BackupFileEntry::new).collect(Collectors.toList()));
                    } catch (BackupReadException e) {
                        e.printStackTrace();
                    }

                    return root;
                }
            };

            loadDomainFilesTask.valueProperty().addListener((obs, old, root) -> {
                filesTreeView.setRoot(root);
                domainsTreeView.setCursor(Cursor.DEFAULT);
                filesTreeView.setCursor(Cursor.DEFAULT);
            });

            new Thread(loadDomainFilesTask).start();
        });

        this.filesTreeView.setCellFactory(view -> new TreeCell<>() {
            {
                itemProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue == null || newValue.getFile().isEmpty()) return;
                    setContextMenu(FileActions.getContextMenu(newValue.getFile().get(), splitPane.getScene().getWindow()));
                });
            }

            @Override
            protected void updateItem(BackupFileEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean isDirectory = item.getFile().filter(f -> f.getFileType() == BackupFile.FileType.DIRECTORY).isPresent();

                    CheckBox checkBox = new CheckBox();
                    checkBox.selectedProperty().bindBidirectional(item.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().bindBidirectional(item.checkBoxIndeterminateProperty());
                    if (isDirectory) checkBox.setAllowIndeterminate(true);

                    HBox graphic = new HBox(8, checkBox);
                    graphic.setAlignment(Pos.CENTER_LEFT);

                    ImageView icon = new ImageView(isDirectory ? folderIcon : fileIcon);
                    icon.setFitWidth(16);
                    icon.setFitHeight(16);
                    graphic.getChildren().add(icon);

                    this.getChildren().add(new Label(String.valueOf(item.getSize())));

                    setGraphic(graphic);
                    setText(item.getDisplayName());
                }

            }
        });
    }

    public void insertAsTree(TreeItem<BackupFileEntry> root, List<BackupFileEntry> items) throws BackupReadException {
        HashMap<Integer, List<TreeItem<BackupFileEntry>>> levels = new HashMap<>();

        int maxLevel = 0;

        for (BackupFileEntry item : items) {
            int level = item.getPathLevel();
            if (level > maxLevel) maxLevel = level;
            if (!levels.containsKey(level)) levels.put(item.getPathLevel(), new ArrayList<>());
            TreeItem<BackupFileEntry> treeItem = new TreeItem<>(item);

            item.selectionProperty().addListener((obs, prevSelection, selection) -> {
                boolean noneSelected = selection == BackupFileEntry.Selection.NONE;
                boolean allSelected = selection == BackupFileEntry.Selection.ALL;
                if (treeItem.getParent() != null) {
                    for (TreeItem<BackupFileEntry> sibling : treeItem.getParent().getChildren()) {
                        if (sibling.getValue().getSelection() == BackupFileEntry.Selection.NONE) allSelected = false;
                        else noneSelected = false;
                    }

                    BackupFileEntry parentEntry = treeItem.getParent().getValue();
                    if (allSelected) parentEntry.setSelection(BackupFileEntry.Selection.ALL);
                    else if (noneSelected) parentEntry.setSelection(BackupFileEntry.Selection.NONE);
                    else parentEntry.setSelection(BackupFileEntry.Selection.PARTIAL);
                }

                if (selection != BackupFileEntry.Selection.PARTIAL) {
                    for (TreeItem<BackupFileEntry> child_ : treeItem.getChildren())
                        child_.getValue().setSelection(selection);
                }
            });

            levels.get(level).add(treeItem);
        }

        List<TreeItem<BackupFileEntry>> parents = new ArrayList<>();
        parents.add(root);
        for (int currentLevel = 1; currentLevel <= maxLevel; currentLevel++) {
            List<TreeItem<BackupFileEntry>> children = levels.get(currentLevel);
            if (children == null)
                children = new ArrayList<>();  // will eventually fail but results in a nicer error message

            for (TreeItem<BackupFileEntry> child : children) {
                BackupFileEntry childEntry = child.getValue();

                Optional<TreeItem<BackupFileEntry>> parent = CollectionUtils.find(parents, parentCandidate -> {
                    BackupFileEntry parentEntry = parentCandidate.getValue();

                    if (parentEntry.getFile().isEmpty()) return false;
                    BackupFile parentFile = parentEntry.getFile().get();

                    return parentFile.getFileType() == BackupFile.FileType.DIRECTORY
                            && parentFile.domain.equals(childEntry.getDomain())
                            && childEntry.getParentPath().equals(parentFile.relativePath);
                });

                if (parent.isPresent()) {
                    parent.get().getChildren().add(child);
                } else {
                    throw new BackupReadException("Missing parent directory: " + childEntry.getDomain() + "-" + BackupPathUtils.getParentPath(childEntry.getRelativePath()));
                }
            }

            parents = children;
        }
    }

    public void tabShown(ITunesBackup backup) {
        if (backup == this.selectedBackup) return;

        this.selectedBackup = backup;

        if (this.loadDomainFilesTask != null) this.loadDomainFilesTask.cancel(true);
        this.filesTreeView.setRoot(null);

        List<BackupFile> domains = backup.queryDomainRoots();

        TreeItem<BackupFileEntry> root = new TreeItem<>();

        TreeItem<BackupFileEntry> apps = new TreeItem<>(new BackupFileEntry("Applications"));
        TreeItem<BackupFileEntry> appGroups = new TreeItem<>(new BackupFileEntry("Application Groups"));
        TreeItem<BackupFileEntry> appPlugins = new TreeItem<>(new BackupFileEntry("Application Plugins"));
        TreeItem<BackupFileEntry> sysContainers = new TreeItem<>(new BackupFileEntry("System Containers"));
        TreeItem<BackupFileEntry> sysSharedContainers = new TreeItem<>(new BackupFileEntry("System Shared Containers"));

        for (BackupFile file : domains) {
            String domain = file.domain;
            TreeItem<BackupFileEntry> item = new TreeItem<>(new BackupFileEntry(file));

            if (domain.startsWith("AppDomain-")) apps.getChildren().add(item);
            else if (domain.startsWith("AppDomainGroup-")) appGroups.getChildren().add(item);
            else if (domain.startsWith("AppDomainPlugin-")) appPlugins.getChildren().add(item);
            else if (domain.startsWith("SysContainerDomain-")) sysContainers.getChildren().add(item);
            else if (domain.startsWith("SysSharedContainerDomain-")) sysSharedContainers.getChildren().add(item);
            else root.getChildren().add(item);
        }

        List<TreeItem<BackupFileEntry>> domainGroups = Arrays.asList(apps, appGroups, appPlugins, sysContainers, sysSharedContainers);
        for (TreeItem<BackupFileEntry> domainGroup : domainGroups) {
            domainGroup.getValue().selectionProperty().addListener((observable, prevSelection, selection) ->
                    domainGroup.getChildren().forEach(group -> group.getValue().setSelection(selection))
            );
        }

        root.getChildren().addAll(domainGroups);

        this.domainsTreeView.setRoot(root);
    }

    private Stream<TreeItem<BackupFileEntry>> flattenAllChildren(TreeItem<BackupFileEntry> parent) {
        if (parent.isLeaf()) return Stream.empty();

        return Stream.concat(parent.getChildren().stream(), parent.getChildren().stream().flatMap(this::flattenAllChildren));
    }

    @FXML
    public void exportSelectedFiles() {
        DirectoryChooser chooser = new DirectoryChooser();
        File destination = chooser.showDialog(splitPane.getScene().getWindow());
        if (destination == null) return;

        List<BackupFile> selectedFiles = flattenAllChildren(filesTreeView.getRoot())
                .map(TreeItem::getValue)
                .filter(entry -> entry.getSelection() != BackupFileEntry.Selection.NONE)
                .map(BackupFileEntry::getFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        Task<Void> extractTask = exportFiles(selectedFiles, destination);

        Dialogs.ProgressAlert progress = new Dialogs.ProgressAlert("Extracting...", extractTask, true);
        new Thread(extractTask).start();
        progress.showAndWait();
    }

    @FXML
    public void exportSelectedDomains() {
        DirectoryChooser chooser = new DirectoryChooser();
        File destination = chooser.showDialog(splitPane.getScene().getWindow());
        if (destination == null) return;

        String[] selectedDomains = flattenAllChildren(domainsTreeView.getRoot())
                .map(TreeItem::getValue)
                .filter(entry -> entry.getSelection() != BackupFileEntry.Selection.NONE)
                .map(BackupFileEntry::getFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(file -> file.domain)
                .toArray(String[]::new);

        List<BackupFile> selectedFiles = selectedBackup.queryDomainFiles(true, selectedDomains);

        Task<Void> extractTask = exportFiles(selectedFiles, destination);

        Dialogs.ProgressAlert progress = new Dialogs.ProgressAlert("Extracting...", extractTask, true);
        new Thread(extractTask).start();
        progress.showAndWait();
    }

    private Task<Void> exportFiles(List<BackupFile> files, File destination) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                ButtonType skipButtonType = new ButtonType("Skip", ButtonBar.ButtonData.NEXT_FORWARD);
                ButtonType skipAllExistingButtonType = new ButtonType("Skip all existing", ButtonBar.ButtonData.NEXT_FORWARD);
                boolean skipExisting = false;

                for (int i = 0; i < files.size(); i++) {
                    try {
                        if (Thread.interrupted()) break;
                        files.get(i).extractToFolder(destination, true);
                        updateProgress(i, files.size());
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
                        e.printStackTrace();
                        Optional<ButtonType> response = showFileExportError(
                                e.getMessage() + "\nContinue?", ButtonType.YES, ButtonType.CANCEL);
                        if (response.isEmpty() || response.get() == ButtonType.CANCEL) break;
                    }
                }

                return null;
            }
        };
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
