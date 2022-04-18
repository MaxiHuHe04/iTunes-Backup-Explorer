package me.maxih.itunes_backup_explorer.ui;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;
import me.maxih.itunes_backup_explorer.api.BackupReadException;
import me.maxih.itunes_backup_explorer.api.ITunesBackup;
import me.maxih.itunes_backup_explorer.api.NotUnlockedException;
import me.maxih.itunes_backup_explorer.api.UnsupportedCryptoException;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

public class WindowController {
    static final DateFormat BACKUP_DATE_FMT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    List<ITunesBackup> backups = new ArrayList<>();
    ITunesBackup selectedBackup;
    final Map<ITunesBackup, ToggleButton> sidebarButtons = new HashMap<>();

    List<Node> lockedTabPages = new ArrayList<>();

    @FXML
    VBox backupSidebarBox;

    @FXML
    TabPane tabPane;

    @FXML
    AnchorPane infoTabPage;
    @FXML
    InfoTabController infoTabPageController;

    @FXML
    AnchorPane fileSearchTabPage;
    @FXML
    FileSearchTabController fileSearchTabPageController;

    @FXML
    AnchorPane filesTabPage;
    @FXML
    FilesTabController filesTabPageController;


    @FXML
    public void initialize() {
        this.lockedTabPages = Arrays.asList(this.filesTabPage, this.fileSearchTabPage);

        this.tabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            Node tabPage = newTab.getContent();
            if (this.lockedTabPages.contains(tabPage) && !this.tryUnlock()) this.tabPane.getSelectionModel().select(oldTab);
            else if (tabPage == this.filesTabPage) this.filesTabPageController.tabShown(this.selectedBackup);
            else if (tabPage == this.fileSearchTabPage) this.fileSearchTabPageController.tabShown(this.selectedBackup);
        });

        this.loadBackups();
    }

    public void cleanUp() {
        this.backups.forEach(ITunesBackup::cleanUp);
    }

    public void loadBackups() {
        this.backupSidebarBox.getChildren().clear();
        this.backups = new ArrayList<>();
        for (String root : PreferencesController.getBackupRoots()) {
            this.backups.addAll(ITunesBackup.getBackups(new File(root)));
        }
        this.backups.forEach(backup -> {
            ToggleButton backupEntry = new ToggleButton(backup.manifest.deviceName + "\n" + BACKUP_DATE_FMT.format(backup.manifest.date));
            backupEntry.getStyleClass().add("sidebar-button");
            backupEntry.setOnAction(this::backupSelected);
            backupEntry.setMaxWidth(Integer.MAX_VALUE);
            backupEntry.setPrefHeight(60);
            backupEntry.setId(backup.directory.getName());

            MenuItem openBackupDirectory = new MenuItem("Open backup directory");
            openBackupDirectory.setOnAction(event -> {
                try {
                    Desktop.getDesktop().browse(backup.directory.toURI());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            ContextMenu backupContextMenu = new ContextMenu(openBackupDirectory);
            backupEntry.setContextMenu(backupContextMenu);

            this.backupSidebarBox.getChildren().add(backupEntry);

            this.sidebarButtons.put(backup, backupEntry);
            if (backup == this.backups.get(0)) selectBackup(backup);
        });
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean tryUnlock() {
        if (!this.selectedBackup.isLocked()) return true;
        if (this.selectedBackup.manifest.getKeyBag().isEmpty()) return false;

        Optional<String> response = Dialogs.askPassword();
        if (response.isEmpty()) return false;
        String password = response.get();

        try {
            selectedBackup.manifest.getKeyBag().get().unlock(password);
            selectedBackup.decryptDatabase();
            return true;
        } catch (InvalidKeyException e) {
            new Alert(Alert.AlertType.ERROR, "The given password is not valid").showAndWait();
        } catch (BackupReadException e) {
            new Alert(Alert.AlertType.ERROR, "The backup could not be read").showAndWait();
        } catch (UnsupportedCryptoException e) {
            new Alert(Alert.AlertType.ERROR, "Your system doesn't support the necessary cryptography").showAndWait();
        } catch (NotUnlockedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
        return false;
    }

    public void selectBackup(ITunesBackup backup) {
        if (backup == this.selectedBackup) return;

        if (selectedBackup != null) sidebarButtons.get(selectedBackup).setSelected(false);
        this.sidebarButtons.get(backup).setSelected(true);
        this.selectedBackup = backup;

        this.infoTabPageController.updateInformation(backup.manifest);

        Node selectedTabPage = this.tabPane.getSelectionModel().getSelectedItem().getContent();
        if (this.lockedTabPages.contains(selectedTabPage) && !this.tryUnlock()) this.tabPane.getSelectionModel().select(0);
        else if (selectedTabPage == this.filesTabPage) this.filesTabPageController.tabShown(backup);
        else if (selectedTabPage == this.fileSearchTabPage) this.fileSearchTabPageController.tabShown(backup);
    }

    @FXML
    public void backupSelected(ActionEvent event) {
        ToggleButton button = (ToggleButton) event.getSource();

        this.sidebarButtons.entrySet().stream()
                .filter(entry -> entry.getValue() == button)
                .findFirst()
                .map(Map.Entry::getKey)
                .ifPresent(this::selectBackup);
    }

    @FXML
    public void openPreferences() {
        FXMLLoader fxmlLoader = new FXMLLoader(ITunesBackupExplorer.class.getResource("preferences.fxml"));
        try {
            Parent root = fxmlLoader.load();
            PreferencesController controller = fxmlLoader.getController();
            controller.reloadCallback = this::loadBackups;

            Stage prefsWindow = new Stage();
            prefsWindow.initModality(Modality.APPLICATION_MODAL);
            prefsWindow.initOwner(tabPane.getScene().getWindow());

            Scene prefsScene = new Scene(root, 600, 400);
            prefsWindow.setScene(prefsScene);
            prefsWindow.setTitle("Preferences");
            prefsWindow.getIcons().addAll(((Stage) tabPane.getScene().getWindow()).getIcons());
            prefsWindow.show();
        } catch (IOException e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, e.getMessage());
        }
    }
}
