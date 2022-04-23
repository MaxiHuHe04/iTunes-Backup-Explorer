package me.maxih.itunes_backup_explorer.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import me.maxih.itunes_backup_explorer.api.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class FileActions {

    public static void openFile(BackupFile file) {
        try {
            String ext = file.getFileExtension();
            File tempFile = Files.createTempFile(file.getFileName(), ext.length() > 0 ? ("." + ext) : ".txt").toFile();
            tempFile.deleteOnExit();
            file.extract(tempFile);
            Desktop.getDesktop().open(tempFile);
        } catch (IOException | UnsupportedCryptoException | NotUnlockedException |
                 BackupReadException exception) {
            exception.printStackTrace();
            Dialogs.showAlert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
        }
    }

    public static void extractFile(BackupFile file, Window chooserOwnerWindow) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(file.getFileName());
        String ext = file.getFileExtension();
        if (ext.length() > 0)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext, "*." + ext));
        File destination = chooser.showSaveDialog(chooserOwnerWindow);
        if (destination == null) return;

        try {
            file.extract(destination);
        } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
            e.printStackTrace();
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        }
    }

    public static void replaceFile(BackupFile file, Window chooserOwnerWindow) {
        FileChooser chooser = new FileChooser();
        String ext = file.getFileExtension();
        if (ext.length() > 0)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext, "*." + ext));
        File source = chooser.showOpenDialog(chooserOwnerWindow);
        if (source == null) return;

        try {
            file.replaceWith(source);
            file.backup.reEncryptDatabase();
        } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException |
                 DatabaseConnectionException e) {
            e.printStackTrace();
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        }
    }

    public static void deleteFile(BackupFile file, Runnable removeCallback) {
        Alert confirmation = Dialogs.getAlert(Alert.AlertType.CONFIRMATION,
                "Do you really want to delete this file?",
                ButtonType.YES, ButtonType.CANCEL
        );
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return;

        try {
            file.delete();
            file.backup.reEncryptDatabase();
            removeCallback.run();
        } catch (IOException | DatabaseConnectionException | BackupReadException | UnsupportedCryptoException |
                 NotUnlockedException e) {
            e.printStackTrace();
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        }
    }

    public static void insertFiles(BackupFile directory, Window chooserOwnerWindow) {
        FileChooser chooser = new FileChooser();
        List<File> files = chooser.showOpenMultipleDialog(chooserOwnerWindow);
        if (files == null) return;

        for (File file : files) {
            System.out.println(file.getAbsolutePath());
            // TODO: insert files
        }
    }

    public static ContextMenu getContextMenu(BackupFile file, Window ownerWindow, Runnable removeCallback) {
        MenuItem openFileItem = new MenuItem("Open file");
        openFileItem.setOnAction(event -> FileActions.openFile(file));

        MenuItem extractFileItem = new MenuItem("Extract file...");
        extractFileItem.setOnAction(event -> FileActions.extractFile(file, ownerWindow));

        MenuItem replaceItem = new MenuItem("Replace...");
        replaceItem.setOnAction(event -> FileActions.replaceFile(file, ownerWindow));

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(event -> FileActions.deleteFile(file, removeCallback));

        MenuItem insertFilesItem = new MenuItem("Insert files...");
        insertFilesItem.setDisable(true);  // TODO: implement insertFiles and enable
        insertFilesItem.setOnAction(event -> FileActions.insertFiles(file, ownerWindow));

        ContextMenu menu = new ContextMenu();

        if (file.getFileType() == BackupFile.FileType.DIRECTORY)
            menu.getItems().add(insertFilesItem);
        else if (file.getFileType() == BackupFile.FileType.FILE)
            menu.getItems().addAll(openFileItem, extractFileItem, replaceItem, deleteItem);

        return menu;
    }

    private FileActions() {
    }
}
