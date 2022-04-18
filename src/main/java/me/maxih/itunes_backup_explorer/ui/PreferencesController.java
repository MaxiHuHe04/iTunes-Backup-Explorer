package me.maxih.itunes_backup_explorer.ui;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PreferencesController {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(ITunesBackupExplorer.class);

    private static final String DEFAULT_ROOTS = Stream.of(
            Paths.get(System.getProperty("user.home"), "AppData\\Roaming\\Apple Computer\\MobileSync\\Backup"),
            Paths.get(System.getProperty("user.home"), "Apple\\MobileSync\\Backup"),
            Paths.get(System.getProperty("user.home"), "Library/Application Support/MobileSync/Backup")  // macOS
    ).filter(Files::exists).map(Path::toString).collect(Collectors.joining("\n"));

    public static String[] getBackupRoots() {
        return PREFERENCES.get("BackupRoots", DEFAULT_ROOTS).split("\\n");
    }


    public Runnable reloadCallback;

    @FXML
    public TextArea backupRootsTextArea;

    @FXML
    public void initialize() {
        this.backupRootsTextArea.setText(PREFERENCES.get("BackupRoots", DEFAULT_ROOTS));
    }

    @FXML
    public void save() {
        if (!backupRootsTextArea.getText().equals(PREFERENCES.get("BackupRoots", DEFAULT_ROOTS))) {
            PREFERENCES.put("BackupRoots", backupRootsTextArea.getText());
            if (this.reloadCallback != null) this.reloadCallback.run();
        }

        ((Stage) backupRootsTextArea.getScene().getWindow()).close();
    }

    @FXML
    public void cancel() {
        ((Stage) backupRootsTextArea.getScene().getWindow()).close();
    }

    @FXML
    public void resetToDefaults() {
        String currentRoots = PREFERENCES.get("BackupRoots", DEFAULT_ROOTS);
        if (!currentRoots.equals(DEFAULT_ROOTS)) {
            PREFERENCES.remove("BackupRoots");
            if (this.reloadCallback != null) this.reloadCallback.run();
        }

        ((Stage) backupRootsTextArea.getScene().getWindow()).close();
    }
}
