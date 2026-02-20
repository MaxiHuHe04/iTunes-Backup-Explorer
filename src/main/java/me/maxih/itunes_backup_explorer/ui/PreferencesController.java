package me.maxih.itunes_backup_explorer.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PreferencesController {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(ITunesBackupExplorer.class);

    private static final String KEY_BACKUP_ROOTS = "BackupRoots";
    private static final String KEY_THEME = "Theme";
    private static final String KEY_PRESERVE_TIMESTAMPS = "PreserveTimestamps";
    private static final String KEY_CREATE_DIRECTORY_STRUCTURE = "CreateDirectoryStructure";
    private static final String KEY_SKIP_EXISTING_FILES = "SkipExistingFiles";
    private static final String KEY_AUTO_SELECT_NEWEST_BACKUP = "AutoSelectNewestBackup";
    private static final String KEY_EXPAND_DOMAIN_GROUPS = "ExpandDomainGroups";
    private static final String KEY_SEARCH_INCLUDE_NON_FILES = "SearchIncludeNonFiles";
    private static final String KEY_SEARCH_RESULT_LIMIT = "SearchResultLimit";
    private static final String KEY_CONFIRM_BEFORE_DELETE = "ConfirmBeforeDelete";
    private static final String KEY_LAST_EXPORT_DIRECTORY = "LastExportDirectory";

    private static final String DEFAULT_THEME = "Dark";
    private static final int DEFAULT_SEARCH_RESULT_LIMIT = 5000;

    private static final String DEFAULT_ROOTS = Stream.of(
            Paths.get(System.getProperty("user.home"), "AppData\\Roaming\\Apple Computer\\MobileSync\\Backup"),
            Paths.get(System.getProperty("user.home"), "Apple\\MobileSync\\Backup"),
            Paths.get(System.getProperty("user.home"), "Library/Application Support/MobileSync/Backup")
    ).filter(Files::exists).map(Path::toString).collect(Collectors.joining("\n"));

    private static final String APP_VERSION = "v2.1";

    public static String[] getBackupRoots() {
        String roots = PREFERENCES.get(KEY_BACKUP_ROOTS, DEFAULT_ROOTS);
        return roots.isEmpty() ? new String[0] : roots.split("\\n");
    }

    public static boolean addBackupRoot(String path) {
        String normalizedPath = Paths.get(path).toAbsolutePath().normalize().toString();
        String[] existing = getBackupRoots();
        for (String root : existing) {
            String normalizedRoot = Paths.get(root.trim()).toAbsolutePath().normalize().toString();
            if (normalizedRoot.equals(normalizedPath)) return false;
            if (normalizedPath.startsWith(normalizedRoot + File.separator)) return false;
            if (normalizedRoot.startsWith(normalizedPath + File.separator)) return false;
        }
        String roots = PREFERENCES.get(KEY_BACKUP_ROOTS, DEFAULT_ROOTS);
        String updated = roots.isEmpty() ? path : roots + "\n" + path;
        PREFERENCES.put(KEY_BACKUP_ROOTS, updated);
        return true;
    }

    public static String getTheme() {
        return PREFERENCES.get(KEY_THEME, DEFAULT_THEME);
    }

    public static boolean getPreserveTimestamps() {
        return PREFERENCES.getBoolean(KEY_PRESERVE_TIMESTAMPS, true);
    }

    public static boolean getCreateDirectoryStructure() {
        return PREFERENCES.getBoolean(KEY_CREATE_DIRECTORY_STRUCTURE, true);
    }

    public static boolean getSkipExistingFiles() {
        return PREFERENCES.getBoolean(KEY_SKIP_EXISTING_FILES, false);
    }

    public static boolean getAutoSelectNewestBackup() {
        return PREFERENCES.getBoolean(KEY_AUTO_SELECT_NEWEST_BACKUP, true);
    }

    public static boolean getExpandDomainGroups() {
        return PREFERENCES.getBoolean(KEY_EXPAND_DOMAIN_GROUPS, true);
    }

    public static boolean getSearchIncludeNonFiles() {
        return PREFERENCES.getBoolean(KEY_SEARCH_INCLUDE_NON_FILES, false);
    }

    public static int getSearchResultLimit() {
        return Math.max(0, PREFERENCES.getInt(KEY_SEARCH_RESULT_LIMIT, DEFAULT_SEARCH_RESULT_LIMIT));
    }

    public static boolean getConfirmBeforeDelete() {
        return PREFERENCES.getBoolean(KEY_CONFIRM_BEFORE_DELETE, true);
    }

    public static File getLastExportDirectory() {
        String path = PREFERENCES.get(KEY_LAST_EXPORT_DIRECTORY, "");
        if (path.isBlank()) return null;
        File file = new File(path);
        return file.isDirectory() ? file : null;
    }

    public static void setLastExportDirectory(File directory) {
        if (directory != null && directory.isDirectory()) {
            PREFERENCES.put(KEY_LAST_EXPORT_DIRECTORY, directory.getAbsolutePath());
        }
    }

    public Runnable reloadCallback;
    public Runnable preferencesChangedCallback;

    @FXML
    public ListView<String> backupRootsListView;

    @FXML
    public ComboBox<String> themeComboBox;

    @FXML
    public CheckBox preserveTimestampsCheckBox;

    @FXML
    public CheckBox createDirectoryStructureCheckBox;

    @FXML
    public CheckBox skipExistingFilesCheckBox;

    @FXML
    public CheckBox autoSelectNewestBackupCheckBox;

    @FXML
    public CheckBox expandDomainGroupsCheckBox;

    @FXML
    public CheckBox searchIncludeNonFilesCheckBox;

    @FXML
    public CheckBox confirmBeforeDeleteCheckBox;

    @FXML
    public Spinner<Integer> searchResultLimitSpinner;

    @FXML
    public Label versionLabel;

    @FXML
    public Label javaVersionLabel;

    @FXML
    public Label platformLabel;

    private ObservableList<String> backupRootsList;

    @FXML
    public void initialize() {
        backupRootsList = FXCollections.observableArrayList();
        String roots = PREFERENCES.get(KEY_BACKUP_ROOTS, DEFAULT_ROOTS);
        if (!roots.isEmpty()) {
            String[] rootsArray = roots.split("\\n");
            for (String root : rootsArray) {
                if (!root.trim().isEmpty()) {
                    backupRootsList.add(root.trim());
                }
            }
        }
        backupRootsListView.setItems(backupRootsList);

        themeComboBox.setItems(FXCollections.observableArrayList("Dark", "Light"));
        themeComboBox.getSelectionModel().select(getTheme());

        preserveTimestampsCheckBox.setSelected(getPreserveTimestamps());
        createDirectoryStructureCheckBox.setSelected(getCreateDirectoryStructure());
        skipExistingFilesCheckBox.setSelected(getSkipExistingFiles());
        autoSelectNewestBackupCheckBox.setSelected(getAutoSelectNewestBackup());
        expandDomainGroupsCheckBox.setSelected(getExpandDomainGroups());
        searchIncludeNonFilesCheckBox.setSelected(getSearchIncludeNonFiles());
        confirmBeforeDeleteCheckBox.setSelected(getConfirmBeforeDelete());

        SpinnerValueFactory.IntegerSpinnerValueFactory valueFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100000, getSearchResultLimit(), 100);
        searchResultLimitSpinner.setValueFactory(valueFactory);
        searchResultLimitSpinner.setEditable(true);

        versionLabel.setText(APP_VERSION);
        javaVersionLabel.setText(System.getProperty("java.version"));
        platformLabel.setText(System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    }

    @FXML
    public void addDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Backup Directory");
        File selected = chooser.showDialog(backupRootsListView.getScene().getWindow());
        if (selected != null && selected.isDirectory()) {
            String path = selected.getAbsolutePath();
            if (!backupRootsList.contains(path)) {
                backupRootsList.add(path);
            }
        }
    }

    @FXML
    public void removeDirectory() {
        String selected = backupRootsListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            backupRootsList.remove(selected);
        }
    }

    @FXML
    public void save() {
        String currentRoots = PREFERENCES.get(KEY_BACKUP_ROOTS, DEFAULT_ROOTS);
        String newRoots = String.join("\n", backupRootsList);

        if (!newRoots.equals(currentRoots)) {
            PREFERENCES.put(KEY_BACKUP_ROOTS, newRoots);
            if (this.reloadCallback != null) this.reloadCallback.run();
        }

        PREFERENCES.put(KEY_THEME, themeComboBox.getSelectionModel().getSelectedItem());
        PREFERENCES.putBoolean(KEY_PRESERVE_TIMESTAMPS, preserveTimestampsCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_CREATE_DIRECTORY_STRUCTURE, createDirectoryStructureCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_SKIP_EXISTING_FILES, skipExistingFilesCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_AUTO_SELECT_NEWEST_BACKUP, autoSelectNewestBackupCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_EXPAND_DOMAIN_GROUPS, expandDomainGroupsCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_SEARCH_INCLUDE_NON_FILES, searchIncludeNonFilesCheckBox.isSelected());
        PREFERENCES.putBoolean(KEY_CONFIRM_BEFORE_DELETE, confirmBeforeDeleteCheckBox.isSelected());
        PREFERENCES.putInt(KEY_SEARCH_RESULT_LIMIT, searchResultLimitSpinner.getValue());

        if (this.preferencesChangedCallback != null) this.preferencesChangedCallback.run();

        ((Stage) backupRootsListView.getScene().getWindow()).close();
    }

    @FXML
    public void cancel() {
        ((Stage) backupRootsListView.getScene().getWindow()).close();
    }

    @FXML
    public void resetToDefaults() {
        PREFERENCES.remove(KEY_BACKUP_ROOTS);
        PREFERENCES.remove(KEY_THEME);
        PREFERENCES.remove(KEY_PRESERVE_TIMESTAMPS);
        PREFERENCES.remove(KEY_CREATE_DIRECTORY_STRUCTURE);
        PREFERENCES.remove(KEY_SKIP_EXISTING_FILES);
        PREFERENCES.remove(KEY_AUTO_SELECT_NEWEST_BACKUP);
        PREFERENCES.remove(KEY_EXPAND_DOMAIN_GROUPS);
        PREFERENCES.remove(KEY_SEARCH_INCLUDE_NON_FILES);
        PREFERENCES.remove(KEY_SEARCH_RESULT_LIMIT);
        PREFERENCES.remove(KEY_CONFIRM_BEFORE_DELETE);

        if (this.reloadCallback != null) this.reloadCallback.run();
        if (this.preferencesChangedCallback != null) this.preferencesChangedCallback.run();

        ((Stage) backupRootsListView.getScene().getWindow()).close();
    }
}
