package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;

import java.util.Optional;

public class Dialogs {

    public record EncryptionChoice(boolean enable, boolean disableAfter, char[] password) {}

    public static Optional<char[]> askPassword() {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle("Enter the password");
        dialog.setHeaderText("This backup is encrypted with a password");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        applyTheme(dialog.getDialogPane());
        ((Stage) dialog.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);

        PasswordField passwordField = new PasswordField();
        dialog.setOnShown(event -> Platform.runLater(passwordField::requestFocus));

        HBox content = new HBox();
        content.setAlignment(Pos.CENTER_LEFT);
        content.setSpacing(10);
        content.getChildren().addAll(new Label("Please type in your password here:"), passwordField);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(pressedButton ->
                pressedButton == ButtonType.OK ? passwordField.getText().toCharArray() : null);

        return dialog.showAndWait();
    }

    public static Optional<EncryptionChoice> askBackupEncryption() {
        Dialog<EncryptionChoice> dialog = new Dialog<>();
        dialog.setTitle("Backup Encryption");
        dialog.setHeaderText("Enable encrypted backup?");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        applyTheme(dialog.getDialogPane());
        ((Stage) dialog.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);

        Label hint = new Label("Encrypted backups can include more data. "
                + "The password will be required to access encrypted backups later.");
        hint.setWrapText(true);

        CheckBox enable = new CheckBox("Enable encrypted backup (recommended for WhatsApp)");
        CheckBox disableAfter = new CheckBox("Disable encryption after backup");
        disableAfter.setDisable(true);

        PasswordField pass1 = new PasswordField();
        pass1.setPromptText("Password");
        PasswordField pass2 = new PasswordField();
        pass2.setPromptText("Confirm password");

        Label mismatch = new Label();
        mismatch.setStyle("-fx-text-fill: #d96f6f;");

        GridPane grid = new GridPane();
        grid.setVgap(6);
        grid.setHgap(8);
        grid.add(new Label("Password:"), 0, 0);
        grid.add(pass1, 1, 0);
        grid.add(new Label("Confirm:"), 0, 1);
        grid.add(pass2, 1, 1);

        VBox content = new VBox(10, hint, enable, grid, mismatch, disableAfter);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(5, 5, 5, 5));

        Runnable updateState = () -> {
            boolean on = enable.isSelected();
            pass1.setDisable(!on);
            pass2.setDisable(!on);
            grid.setDisable(!on);
            grid.setManaged(on);
            grid.setVisible(on);
            mismatch.setManaged(on);
            mismatch.setVisible(on);
            disableAfter.setDisable(!on);
            if (!on) disableAfter.setSelected(false);
        };
        enable.selectedProperty().addListener((obs, oldVal, newVal) -> updateState.run());
        updateState.run();

        mismatch.textProperty().bind(
                Bindings.when(enable.selectedProperty()
                                .and(pass1.textProperty().isNotEmpty())
                                .and(pass2.textProperty().isNotEmpty())
                                .and(pass1.textProperty().isNotEqualTo(pass2.textProperty())))
                        .then("Passwords do not match")
                        .otherwise("")
        );

        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.disableProperty().bind(
                enable.selectedProperty()
                        .and(pass1.textProperty().isEmpty()
                                .or(pass2.textProperty().isEmpty())
                                .or(pass1.textProperty().isNotEqualTo(pass2.textProperty())))
        );

        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            if (!enable.isSelected()) return new EncryptionChoice(false, false, null);
            return new EncryptionChoice(true, disableAfter.isSelected(), pass1.getText().toCharArray());
        });

        return dialog.showAndWait();
    }

    public static Alert getAlert(Alert.AlertType type, String message, ButtonType... buttonTypes) {
        Alert alert = new Alert(type, message, buttonTypes);
        applyTheme(alert.getDialogPane());
        ((Stage) alert.getDialogPane().getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);
        return alert;
    }

    public static Optional<ButtonType> showAlert(Alert.AlertType type, String message, ButtonType... buttonTypes) {
        return getAlert(type, message, buttonTypes).showAndWait();
    }

    public static class ProgressAlert extends Stage {
        private Label messageLabel;

        public ProgressAlert(String title, Task<?> task, EventHandler<WindowEvent> cancelEventHandler) {
            this.initModality(Modality.APPLICATION_MODAL);
            this.setTitle(title);
            this.setResizable(false);
            this.setOnCloseRequest(cancelEventHandler);
            this.getIcons().add(ITunesBackupExplorer.APP_ICON);

            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(250);
            bar.setPadding(new Insets(10));
            bar.progressProperty().bind(task.progressProperty());

            messageLabel = new Label();
            messageLabel.setPadding(new Insets(5, 10, 5, 10));
            messageLabel.textProperty().bind(task.messageProperty());

            VBox container = new VBox(messageLabel, bar);
            container.setAlignment(Pos.CENTER);
            container.setPadding(new Insets(10));

            task.runningProperty().addListener((observable, oldValue, newValue) -> {
                if (oldValue && !newValue) this.close();
            });

            this.setScene(new Scene(container));
        }
        public ProgressAlert(String title, Task<?> task, boolean cancellable) {
            this(title, task, cancellable ? event -> task.cancel() : Event::consume);
        }

        public ProgressAlert(String title, Task<?> task, Runnable cancelAction) {
            this(title, task, event -> cancelAction.run());
        }

    }

    private static void applyTheme(DialogPane dialogPane) {
        dialogPane.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm()
        );
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        dialogPane.getStyleClass().add(theme);
    }

    private Dialogs() {
    }

}
