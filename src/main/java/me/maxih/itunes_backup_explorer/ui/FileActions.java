package me.maxih.itunes_backup_explorer.ui;

import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import me.maxih.itunes_backup_explorer.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class FileActions {
    private static final Logger logger = LoggerFactory.getLogger(FileActions.class);

    public static void openFile(BackupFile file) {
        try {
            String ext = file.getFileExtension();
            File tempFile = Files.createTempFile(file.getFileName(), ext.length() > 0 ? ("." + ext) : ".txt").toFile();
            tempFile.deleteOnExit();
            file.extract(tempFile);

            Thread opener = new Thread(() -> {
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("linux")) {
                        new ProcessBuilder("xdg-open", tempFile.getAbsolutePath()).start();
                    } else if (os.contains("mac")) {
                        new ProcessBuilder("open", tempFile.getAbsolutePath()).start();
                    } else {
                        Desktop.getDesktop().open(tempFile);
                    }
                } catch (IOException e) {
                    logger.error("Failed to open file with default application", e);
                    javafx.application.Platform.runLater(() ->
                            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK)
                    );
                }
            });
            opener.setDaemon(true);
            opener.start();
        } catch (IOException | UnsupportedCryptoException | NotUnlockedException |
                 BackupReadException exception) {
            logger.error("Failed to open file", exception);
            Dialogs.showAlert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
        }
    }

    public static void extractFile(BackupFile file, Window chooserOwnerWindow) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(file.getFileName());
        File lastDirectory = PreferencesController.getLastExportDirectory();
        if (lastDirectory != null) chooser.setInitialDirectory(lastDirectory);
        String ext = file.getFileExtension();
        if (ext.length() > 0)
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext, "*." + ext));
        File destination = chooser.showSaveDialog(chooserOwnerWindow);
        if (destination == null) return;

        try {
            file.extract(destination, PreferencesController.getPreserveTimestamps());
            PreferencesController.setLastExportDirectory(destination.getParentFile());
        } catch (IOException | BackupReadException | NotUnlockedException | UnsupportedCryptoException e) {
            logger.error("Failed to extract file", e);
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
            logger.error("Failed to replace file", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        }
    }

    public static void showSymlinkTarget(BackupFile file) {
        String target = file.getSymlinkTarget();

        Alert alert = Dialogs.getAlert(Alert.AlertType.INFORMATION, target, ButtonType.CLOSE);
        alert.setTitle("Symbolic link");
        alert.setHeaderText("Target of the symbolic link:");

        TextArea textArea = new TextArea(target);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(2);
        textArea.setPrefColumnCount(30);
        HBox content = new HBox(textArea);

        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

    /**
     * Context menu action to delete files or folders
     * @param file The BackupFile to delete
     * @param removeCallback This is called with the <code>fileID</code>s of the deleted files including children
     */
    public static void delete(BackupFile file, Consumer<List<String>> removeCallback) {
        if (PreferencesController.getConfirmBeforeDelete()) {
            Alert confirmation = Dialogs.getAlert(Alert.AlertType.CONFIRMATION,
                    file.getFileType() == BackupFile.FileType.DIRECTORY
                            ? "Are you sure you want to delete this folder and everything in it?"
                            : "Are you sure you want to delete this file?",
                    ButtonType.YES, ButtonType.CANCEL
            );
            ((Button) confirmation.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
            ((Button) confirmation.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return;
        }

        try {
            List<String> deletedFileIDs = new ArrayList<>();

            if (file.getFileType() == BackupFile.FileType.DIRECTORY) {
                List<BackupFile> children = file.backup.queryAllChildren(file.domain, file.relativePath);
                for (BackupFile f : children) {
                    f.delete();
                    deletedFileIDs.add(f.fileID);
                }
            }

            file.delete();
            deletedFileIDs.add(file.fileID);
            file.backup.reEncryptDatabase();

            removeCallback.accept(deletedFileIDs);
        } catch (IOException | DatabaseConnectionException | BackupReadException | UnsupportedCryptoException |
                 NotUnlockedException e) {
            logger.error("Failed to delete file", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
        }
    }

    public static void insertFiles(BackupFile directory, Window chooserOwnerWindow) {
        FileChooser chooser = new FileChooser();
        List<File> files = chooser.showOpenMultipleDialog(chooserOwnerWindow);
        if (files == null) return;

        for (File file : files) {
            logger.info("Inserting file: {}", file.getAbsolutePath());
            // TODO: insert files
        }
    }

    public static ContextMenu getContextMenu(BackupFile file, Window ownerWindow, Consumer<List<String>> removeCallback) {
        MenuItem openFileItem = new MenuItem("Open file");
        openFileItem.setOnAction(event -> FileActions.openFile(file));

        MenuItem extractFileItem = new MenuItem("Extract file...");
        extractFileItem.setOnAction(event -> FileActions.extractFile(file, ownerWindow));

        MenuItem replaceItem = new MenuItem("Replace...");
        replaceItem.setOnAction(event -> FileActions.replaceFile(file, ownerWindow));

        MenuItem showSymlinkTargetItem = new MenuItem("Show symlink target");
        showSymlinkTargetItem.setOnAction(event -> FileActions.showSymlinkTarget(file));

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setStyle("-fx-text-fill: #900;");
        deleteItem.setOnAction(event -> FileActions.delete(file, removeCallback));

        MenuItem insertFilesItem = new MenuItem("Insert files...");
        insertFilesItem.setDisable(true);  // TODO: implement insertFiles and enable
        insertFilesItem.setOnAction(event -> FileActions.insertFiles(file, ownerWindow));

        ContextMenu menu = new ContextMenu();

        if (file.getFileType() == BackupFile.FileType.DIRECTORY)
            menu.getItems().addAll(insertFilesItem, deleteItem);
        else if (file.getFileType() == BackupFile.FileType.FILE)
            menu.getItems().addAll(openFileItem, extractFileItem, replaceItem, deleteItem);
        else if (file.getFileType() == BackupFile.FileType.SYMBOLIC_LINK)
            menu.getItems().addAll(showSymlinkTargetItem, deleteItem);

        return menu;
    }

    private FileActions() {
    }
}
