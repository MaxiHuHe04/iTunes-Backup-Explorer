package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import me.maxih.itunes_backup_explorer.api.*;
import me.maxih.itunes_backup_explorer.util.BackupPathUtils;
import me.maxih.itunes_backup_explorer.util.CollectionUtils;
import me.maxih.itunes_backup_explorer.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FilesTabController {
    private static final Logger logger = LoggerFactory.getLogger(FilesTabController.class);

    private ITunesBackup selectedBackup;
    private Task<TreeItem<BackupFileEntry>> loadDomainFilesTask;
    private List<BackupFile> currentDomainFiles = Collections.emptyList();
    private boolean fileCountUpdatePending = false;

    @FXML
    SplitPane splitPane;

    @FXML
    TreeView<BackupFileEntry> domainsTreeView;

    @FXML
    TreeView<BackupFileEntry> filesTreeView;

    @FXML
    Label selectedDomainsCount;

    @FXML
    Label selectedFilesCount;

    @FXML
    TextField fileFilterField;

    @FXML
    ComboBox<String> sortComboBox;

    @FXML
    Button expandAllButton;

    @FXML
    Button collapseAllButton;

    @FXML
    Button selectAllFilesButton;

    @FXML
    CheckBox filesOnlyFilterCheckBox;

    @FXML
    public void initialize() {
        setupDomainTree();
        setupFilesTree();

        sortComboBox.setItems(FXCollections.observableArrayList(
                "Path (A-Z)",
                "Path (Z-A)",
                "Size (Largest first)",
                "Size (Smallest first)",
                "Type (Folders first)"
        ));
        sortComboBox.setValue("Path (A-Z)");

        fileFilterField.textProperty().addListener((obs, oldValue, newValue) -> refreshCurrentDomainTree());
        sortComboBox.valueProperty().addListener((obs, oldValue, newValue) -> refreshCurrentDomainTree());
        filesOnlyFilterCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> refreshCurrentDomainTree());

        setFileControlsEnabled(false);
    }

    private void setupDomainTree() {
        this.domainsTreeView.setCellFactory(view -> new TreeCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final ImageView iconView = new ImageView();
            private final HBox graphic = new HBox(8, checkBox, iconView);
            private BackupFileEntry boundItem;

            {
                graphic.setAlignment(Pos.CENTER_LEFT);
                iconView.setFitWidth(32);
                iconView.setFitHeight(32);
            }

            @Override
            protected void updateItem(BackupFileEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (boundItem != null) {
                    checkBox.selectedProperty().unbindBidirectional(boundItem.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().unbindBidirectional(boundItem.checkBoxIndeterminateProperty());
                    boundItem = null;
                }

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setPrefHeight(36);

                    checkBox.selectedProperty().bindBidirectional(item.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().bindBidirectional(item.checkBoxIndeterminateProperty());
                    boundItem = item;

                    iconView.setImage(item.getIcon());
                    setGraphic(graphic);
                    setText(item.getDisplayName());

                    if (getDisclosureNode() != null) getDisclosureNode().setTranslateY(8);
                }
            }
        });

        domainsTreeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.getValue() == null || newValue.getValue().getFile().isEmpty()) return;
            if (loadDomainFilesTask != null) loadDomainFilesTask.cancel(true);

            domainsTreeView.setCursor(Cursor.WAIT);
            filesTreeView.setCursor(Cursor.WAIT);

            BackupFile domainRoot = newValue.getValue().getFile().get();
            loadDomainFilesTask = new Task<>() {
                @Override
                protected TreeItem<BackupFileEntry> call() {
                    TreeItem<BackupFileEntry> root = new TreeItem<>(new BackupFileEntry(domainRoot));
                    try {
                        List<BackupFile> result = selectedBackup.queryDomainFiles(false, domainRoot.domain);
                        currentDomainFiles = result;
                        buildFilteredTree(root, result);
                    } catch (DatabaseConnectionException | BackupReadException e) {
                        logger.error("Failed to load domain files", e);
                    }

                    return root;
                }
            };

            loadDomainFilesTask.valueProperty().addListener((obs, oldRoot, root) -> {
                filesTreeView.setRoot(root);
                domainsTreeView.setCursor(Cursor.DEFAULT);
                filesTreeView.setCursor(Cursor.DEFAULT);
                updateFileSelectionCount();
                setFileControlsEnabled(true);
            });

            new Thread(loadDomainFilesTask).start();
        });
    }

    private void setupFilesTree() {
        this.filesTreeView.setCellFactory(view -> new TreeCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final ImageView iconView = new ImageView();
            private final HBox graphic = new HBox(8, checkBox, iconView);
            private BackupFileEntry boundItem;

            {
                graphic.setAlignment(Pos.CENTER_LEFT);
                iconView.setFitWidth(16);
                iconView.setFitHeight(16);

                itemProperty().addListener((observable, oldValue, newValue) -> {
                    if (newValue == null || newValue.getFile().isEmpty() || getTreeItem() == null || getTreeItem().getParent() == null) {
                        setContextMenu(null);
                        return;
                    }
                    TreeItem<BackupFileEntry> parent = getTreeItem().getParent();
                    setContextMenu(FileActions.getContextMenu(
                            newValue.getFile().get(),
                            splitPane.getScene().getWindow(),
                            removedIDs -> parent.getChildren().remove(getTreeItem()))
                    );
                });
            }

            @Override
            protected void updateItem(BackupFileEntry item, boolean empty) {
                super.updateItem(item, empty);

                if (boundItem != null) {
                    checkBox.selectedProperty().unbindBidirectional(boundItem.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().unbindBidirectional(boundItem.checkBoxIndeterminateProperty());
                    boundItem = null;
                }

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    checkBox.selectedProperty().bindBidirectional(item.checkBoxSelectedProperty());
                    checkBox.indeterminateProperty().bindBidirectional(item.checkBoxIndeterminateProperty());
                    boundItem = item;

                    iconView.setImage(item.getIcon());
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
                    for (TreeItem<BackupFileEntry> child_ : treeItem.getChildren()) {
                        child_.getValue().setSelection(selection);
                    }
                }

                scheduleFileSelectionCountUpdate();
            });

            levels.get(level).add(treeItem);
        }

        List<TreeItem<BackupFileEntry>> parents = new ArrayList<>();
        parents.add(root);
        for (int currentLevel = 1; currentLevel <= maxLevel; currentLevel++) {
            List<TreeItem<BackupFileEntry>> children = levels.get(currentLevel);
            if (children == null) children = new ArrayList<>();

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
        if (backup == this.selectedBackup && this.domainsTreeView.getRoot() != null) return;

        this.selectedBackup = backup;

        if (this.loadDomainFilesTask != null) this.loadDomainFilesTask.cancel(true);
        this.filesTreeView.setRoot(null);
        this.currentDomainFiles = Collections.emptyList();
        this.fileFilterField.clear();
        this.filesOnlyFilterCheckBox.setSelected(false);
        setFileControlsEnabled(false);

        List<BackupFile> domains;
        try {
            domains = backup.queryDomainRoots();
        } catch (DatabaseConnectionException e) {
            logger.error("Failed to query domain roots", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
            domains = Collections.emptyList();
        }

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
            domainGroup.getValue().selectionProperty().addListener((observable, prevSelection, selection) -> {
                domainGroup.getChildren().forEach(group -> group.getValue().setSelection(selection));
                updateDomainSelectionCount();
            });
        }

        for (BackupFile file : domains) {
            TreeItem<BackupFileEntry> item = this.findDomainTreeItem(root, file.domain);
            if (item != null) {
                item.getValue().selectionProperty().addListener((obs, old, selection) -> updateDomainSelectionCount());
            }
        }

        root.getChildren().addAll(domainGroups);

        this.domainsTreeView.setRoot(root);
        if (PreferencesController.getExpandDomainGroups()) {
            domainGroups.forEach(group -> group.setExpanded(true));
        }
        updateDomainSelectionCount();
        updateFileSelectionCount();
    }

    private TreeItem<BackupFileEntry> findDomainTreeItem(TreeItem<BackupFileEntry> root, String domain) {
        for (TreeItem<BackupFileEntry> child : flattenAllChildren(root).collect(Collectors.toList())) {
            if (child.getValue().getFile().isPresent() && child.getValue().getFile().get().domain.equals(domain)) {
                return child;
            }
        }
        return null;
    }

    private void refreshCurrentDomainTree() {
        TreeItem<BackupFileEntry> currentRoot = filesTreeView.getRoot();
        if (currentRoot == null || currentRoot.getValue() == null || currentRoot.getValue().getFile().isEmpty()) return;

        TreeItem<BackupFileEntry> newRoot = new TreeItem<>(new BackupFileEntry(currentRoot.getValue().getFile().get()));
        try {
            buildFilteredTree(newRoot, currentDomainFiles);
        } catch (BackupReadException e) {
            logger.error("Failed to apply local file filter", e);
        }

        filesTreeView.setRoot(newRoot);
        updateFileSelectionCount();
    }

    private void buildFilteredTree(TreeItem<BackupFileEntry> root, List<BackupFile> source) throws BackupReadException {
        List<BackupFile> filtered = applyLocalFilter(source);
        List<BackupFileEntry> entries = filtered.stream().map(BackupFileEntry::new).collect(Collectors.toList());
        insertAsTree(root, entries);
        sortTree(root, getTreeComparator());
    }

    private List<BackupFile> applyLocalFilter(List<BackupFile> source) {
        if (source == null) return Collections.emptyList();

        String query = fileFilterField.getText() == null ? "" : fileFilterField.getText().trim().toLowerCase(Locale.ROOT);
        boolean filesOnly = filesOnlyFilterCheckBox.isSelected();

        if (query.isEmpty() && !filesOnly) return new ArrayList<>(source);

        Map<String, BackupFile> byPath = source.stream().collect(Collectors.toMap(f -> f.relativePath, f -> f, (first, second) -> first));
        LinkedHashSet<BackupFile> visible = new LinkedHashSet<>();

        for (BackupFile file : source) {
            if (filesOnly && file.getFileType() == BackupFile.FileType.SYMBOLIC_LINK) continue;

            if (!query.isEmpty()) {
                String haystack = (file.relativePath + " " + file.getFileName()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) continue;
            }

            if (filesOnly && file.getFileType() == BackupFile.FileType.DIRECTORY) continue;

            visible.add(file);
            String parentPath = file.getParentPath();
            while (!parentPath.isEmpty()) {
                BackupFile parent = byPath.get(parentPath);
                if (parent == null) break;
                visible.add(parent);
                parentPath = parent.getParentPath();
            }
        }

        return new ArrayList<>(visible);
    }

    private Comparator<TreeItem<BackupFileEntry>> getTreeComparator() {
        String mode = sortComboBox.getValue();

        Comparator<TreeItem<BackupFileEntry>> byPath = Comparator.comparing(item -> item.getValue().getRelativePath().toLowerCase(Locale.ROOT));
        Comparator<TreeItem<BackupFileEntry>> bySize = Comparator.comparingLong(item -> item.getValue().getSize());
        Comparator<TreeItem<BackupFileEntry>> byType = Comparator.<TreeItem<BackupFileEntry>>comparingInt(item -> {
            BackupFile file = item.getValue().getFile().orElse(null);
            if (file == null) return 0;
            return switch (file.getFileType()) {
                case DIRECTORY -> 0;
                case FILE -> 1;
                case SYMBOLIC_LINK -> 2;
            };
        }).thenComparing(byPath);

        if ("Path (Z-A)".equals(mode)) return byPath.reversed();
        if ("Size (Largest first)".equals(mode)) return bySize.reversed().thenComparing(byPath);
        if ("Size (Smallest first)".equals(mode)) return bySize.thenComparing(byPath);
        if ("Type (Folders first)".equals(mode)) return byType;
        return byPath;
    }

    private void sortTree(TreeItem<BackupFileEntry> parent, Comparator<TreeItem<BackupFileEntry>> comparator) {
        if (parent == null || parent.isLeaf()) return;
        parent.getChildren().sort(comparator);
        for (TreeItem<BackupFileEntry> child : parent.getChildren()) {
            sortTree(child, comparator);
        }
    }

    private void updateDomainSelectionCount() {
        if (domainsTreeView.getRoot() == null) {
            selectedDomainsCount.setText("0 domains selected");
            return;
        }

        long count = flattenAllChildren(domainsTreeView.getRoot())
                .map(TreeItem::getValue)
                .filter(entry -> entry.getSelection() != BackupFileEntry.Selection.NONE)
                .filter(entry -> entry.getFile().isPresent())
                .count();

        selectedDomainsCount.setText(count + " domain" + (count != 1 ? "s" : "") + " selected");
    }

    private void scheduleFileSelectionCountUpdate() {
        if (!fileCountUpdatePending) {
            fileCountUpdatePending = true;
            Platform.runLater(() -> {
                fileCountUpdatePending = false;
                updateFileSelectionCount();
            });
        }
    }

    private void updateFileSelectionCount() {
        if (filesTreeView.getRoot() == null) {
            selectedFilesCount.setText("0 files selected");
            return;
        }

        long count = flattenAllChildren(filesTreeView.getRoot())
                .map(TreeItem::getValue)
                .filter(entry -> entry.getSelection() != BackupFileEntry.Selection.NONE)
                .filter(entry -> entry.getFile().isPresent())
                .count();

        selectedFilesCount.setText(count + " file" + (count != 1 ? "s" : "") + " selected");
    }

    private Stream<TreeItem<BackupFileEntry>> flattenAllChildren(TreeItem<BackupFileEntry> parent) {
        if (parent.isLeaf()) return Stream.empty();

        return Stream.concat(parent.getChildren().stream(), parent.getChildren().stream().flatMap(this::flattenAllChildren));
    }

    private void setFileControlsEnabled(boolean enabled) {
        fileFilterField.setDisable(!enabled);
        sortComboBox.setDisable(!enabled);
        filesOnlyFilterCheckBox.setDisable(!enabled);
        expandAllButton.setDisable(!enabled);
        collapseAllButton.setDisable(!enabled);
        selectAllFilesButton.setDisable(!enabled);
    }

    @FXML
    public void expandAllFiles() {
        if (filesTreeView.getRoot() == null) return;
        flattenAllChildren(filesTreeView.getRoot()).forEach(item -> item.setExpanded(true));
    }

    @FXML
    public void collapseAllFiles() {
        if (filesTreeView.getRoot() == null) return;
        flattenAllChildren(filesTreeView.getRoot()).forEach(item -> item.setExpanded(false));
    }

    @FXML
    public void selectAllDomains() {
        if (domainsTreeView.getRoot() == null) return;
        flattenAllChildren(domainsTreeView.getRoot())
            .forEach(item -> item.getValue().setSelection(BackupFileEntry.Selection.ALL));
        updateDomainSelectionCount();
    }

    @FXML
    public void selectAllFiles() {
        if (filesTreeView.getRoot() == null) return;
        flattenAllChildren(filesTreeView.getRoot())
            .forEach(item -> item.getValue().setSelection(BackupFileEntry.Selection.ALL));
        updateFileSelectionCount();
    }

    @FXML
    public void exportSelectedFiles() {
        if (filesTreeView.getRoot() == null) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File lastDirectory = PreferencesController.getLastExportDirectory();
        if (lastDirectory != null) chooser.setInitialDirectory(lastDirectory);
        File destination = chooser.showDialog(splitPane.getScene().getWindow());
        if (destination == null) return;
        PreferencesController.setLastExportDirectory(destination);

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
        if (domainsTreeView.getRoot() == null) return;

        DirectoryChooser chooser = new DirectoryChooser();
        File lastDirectory = PreferencesController.getLastExportDirectory();
        if (lastDirectory != null) chooser.setInitialDirectory(lastDirectory);
        File destination = chooser.showDialog(splitPane.getScene().getWindow());
        if (destination == null) return;
        PreferencesController.setLastExportDirectory(destination);

        String[] selectedDomains = flattenAllChildren(domainsTreeView.getRoot())
                .map(TreeItem::getValue)
                .filter(entry -> entry.getSelection() != BackupFileEntry.Selection.NONE)
                .map(BackupFileEntry::getFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(file -> file.domain)
                .toArray(String[]::new);

        List<BackupFile> selectedFiles;
        try {
            selectedFiles = selectedBackup.queryDomainFiles(true, selectedDomains);
        } catch (DatabaseConnectionException e) {
            logger.error("Failed to query domain files", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
            return;
        }

        Task<Void> extractTask = exportFiles(selectedFiles, destination);

        Dialogs.ProgressAlert progress = new Dialogs.ProgressAlert("Extracting...", extractTask, true);
        new Thread(extractTask).start();
        progress.showAndWait();
    }

    private Task<Void> exportFiles(List<BackupFile> files, File destination) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (files == null) return null;

                ButtonType skipButtonType = new ButtonType("Skip", ButtonBar.ButtonData.NEXT_FORWARD);
                ButtonType skipAllExistingButtonType = new ButtonType("Skip all existing", ButtonBar.ButtonData.NEXT_FORWARD);
                boolean skipExisting = PreferencesController.getSkipExistingFiles();
                boolean withRelativePath = PreferencesController.getCreateDirectoryStructure();
                boolean preserveTimestamps = PreferencesController.getPreserveTimestamps();

                for (int i = 0; i < files.size(); i++) {
                    if (isCancelled()) break;

                    try {
                        BackupFile file = files.get(i);
                        String fileName = file.relativePath.isEmpty() ? file.domain : file.relativePath;
                        updateMessage("Exporting file " + (i + 1) + " of " + files.size() + ": " + fileName);

                        if (Thread.interrupted()) break;
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
                        logger.error("Failed to export file", e);
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
