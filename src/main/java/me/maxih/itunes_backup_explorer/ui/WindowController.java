package me.maxih.itunes_backup_explorer.ui;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.VBox;
import me.maxih.itunes_backup_explorer.api.BackupReadException;
import me.maxih.itunes_backup_explorer.api.ITunesBackup;

import java.io.File;
import java.security.InvalidKeyException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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
        this.backups = ITunesBackup.getBackups(new File(System.getenv("APPDATA"), "Apple Computer\\MobileSync\\Backup"));
        this.backups.forEach(backup -> {
            ToggleButton backupEntry = new ToggleButton(backup.manifest.deviceName + "\n" + BACKUP_DATE_FMT.format(backup.manifest.date));
            backupEntry.getStyleClass().add("sidebar-button");
            backupEntry.setOnAction(this::backupSelected);
            backupEntry.setMaxWidth(Integer.MAX_VALUE);
            backupEntry.setPrefHeight(60);
            backupEntry.setId(backup.directory.getName());
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
            return false;
        } catch (BackupReadException e) {
            new Alert(Alert.AlertType.ERROR, "The backup could not be read").showAndWait();
            return false;
        }
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

}
