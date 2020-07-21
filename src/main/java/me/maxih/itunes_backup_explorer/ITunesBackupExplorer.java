package me.maxih.itunes_backup_explorer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import me.maxih.itunes_backup_explorer.ui.WindowController;

import java.io.IOException;

public class ITunesBackupExplorer extends Application {
    Scene scene;
    WindowController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(ITunesBackupExplorer.class.getResource("window.fxml"));
        Parent root = fxmlLoader.load();
        controller = fxmlLoader.getController();

        scene = new Scene(root, 800, 500);
        stage.setScene(scene);
        stage.setTitle("iTunes Backup Explorer");
        stage.setMinWidth(500);
        stage.setMinHeight(300);
        stage.getIcons().add(getIcon("icon.png"));
        stage.show();
    }

    @Override
    public void stop() {
        this.controller.cleanUp();
    }

    public static void main(String[] args) {
        launch();
    }

    public static Image getIcon(String name) {
        return new Image(ITunesBackupExplorer.class.getResourceAsStream(name));
    }
}
