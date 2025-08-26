package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;

import java.util.Optional;

public class Dialogs {

    private static void centerOnOwner(Stage popup) {
        Stage owner = Stage.getWindows().stream()
            .filter(window -> window.isFocused() && window instanceof Stage)
            .map(window -> (Stage) window)
            .findFirst()
            .orElse(null);

        Rectangle2D bounds;
        if (owner != null) {
            popup.initOwner(owner);
            popup.initModality(Modality.WINDOW_MODAL);
            bounds = Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight()).get(0).getVisualBounds();
        } else {
            bounds = Screen.getPrimary().getVisualBounds();
        }

        popup.setX(bounds.getMinX() + (bounds.getWidth() - popup.getWidth()) / 2);
        popup.setY(bounds.getMinY() + (bounds.getHeight() - popup.getHeight()) / 2);
        popup.toFront();
        popup.setAlwaysOnTop(true);
    }

    public static Optional<String> askPassword() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Enter the password");
        dialog.setHeaderText("This backup is encrypted with a password");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Stage) dialog.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);

        PasswordField passwordField = new PasswordField();
        dialog.setOnShown(event -> {
            Platform.runLater(passwordField::requestFocus);
            centerOnOwner((Stage) dialog.getDialogPane().getScene().getWindow());
        });

        HBox content = new HBox();
        content.setAlignment(Pos.CENTER_LEFT);
        content.setSpacing(10);
        content.getChildren().addAll(new Label("Please type in your password here:"), passwordField);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(pressedButton -> pressedButton == ButtonType.OK ? passwordField.getText() : null);
        return dialog.showAndWait();
    }

    public static Alert getAlert(AlertType type, String message, ButtonType... buttonTypes) {
        Alert alert = new Alert(type, message, buttonTypes);
        ((Stage) alert.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);
        alert.setOnShown(event -> centerOnOwner((Stage) alert.getDialogPane().getScene().getWindow()));
        return alert;
    }

    public static Optional<ButtonType> showAlert(AlertType type, String message, ButtonType... buttonTypes) {
        Alert alert = getAlert(type, message, buttonTypes);
        alert.show();
        return alert.showAndWait();
    }

    public static class ProgressAlert extends Stage {
        public ProgressAlert(String title, Task<?> task, EventHandler<WindowEvent> cancelEventHandler) {
            initModality(Modality.APPLICATION_MODAL);
            setTitle(title);
            setResizable(false);
            setOnCloseRequest(cancelEventHandler);
            getIcons().add(ITunesBackupExplorer.APP_ICON);

            ProgressBar bar = new ProgressBar();
            bar.setPrefSize(250, 50);
            bar.setPadding(new Insets(10));
            bar.progressProperty().bind(task.progressProperty());
            task.runningProperty().addListener((observable, oldValue, newValue) -> {
                if (oldValue && !newValue) close();
            });

            Label header = new Label(title);
            VBox content = new VBox(10, header, bar);
            content.setPadding(new Insets(10));
            content.setAlignment(Pos.CENTER);

            setScene(new Scene(content));

            setOnShown(event -> centerOnOwner(this));
        }

        public ProgressAlert(String title, Task<?> task, boolean cancellable) {
            this(title, task, cancellable ? event -> task.cancel() : Event::consume);
        }

        public ProgressAlert(String title, Task<?> task, Runnable cancelAction) {
            this(title, task, event -> cancelAction.run());
        }
    }

    private Dialogs() {
    }

    public static void showSuccessDialog(String message) {
        Alert alert = new Alert(AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle("Export successful");
        alert.setHeaderText("Backup successfully exported");
        ((Stage) alert.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);

        alert.setOnShown(event -> centerOnOwner((Stage) alert.getDialogPane().getScene().getWindow()));
        alert.showAndWait();
    }
}