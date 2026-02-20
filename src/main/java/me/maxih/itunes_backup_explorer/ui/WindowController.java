package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;
import me.maxih.itunes_backup_explorer.api.BackupReadException;
import me.maxih.itunes_backup_explorer.api.ITunesBackup;
import me.maxih.itunes_backup_explorer.api.NotUnlockedException;
import me.maxih.itunes_backup_explorer.api.UnsupportedCryptoException;
import me.maxih.itunes_backup_explorer.util.DeviceInfo;
import me.maxih.itunes_backup_explorer.util.DeviceService;
import me.maxih.itunes_backup_explorer.util.FileSize;
import me.maxih.itunes_backup_explorer.util.MediaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WindowController {
    private static final Logger logger = LoggerFactory.getLogger(WindowController.class);
    static final DateTimeFormatter BACKUP_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    List<ITunesBackup> backups = new ArrayList<>();
    ITunesBackup selectedBackup;
    final Map<ITunesBackup, ToggleButton> sidebarButtons = new HashMap<>();

    List<Node> lockedTabPages = new ArrayList<>();
    private boolean mediaToolsSetupOffered;

    @FXML
    VBox backupSidebarBox;

    @FXML
    VBox welcomePane;

    @FXML
    TabPane tabPane;

    @FXML
    AnchorPane infoTabPage;
    @FXML
    InfoTabController infoTabPageController;

    @FXML
    AnchorPane mediaTabPage;
    @FXML
    MediaTabController mediaTabPageController;

    @FXML
    AnchorPane filesTabPage;
    @FXML
    FilesTabController filesTabPageController;

    @FXML
    AnchorPane appsTabPage;
    @FXML
    AppsTabController appsTabPageController;

    @FXML
    AnchorPane fileSearchTabPage;
    @FXML
    FileSearchTabController fileSearchTabPageController;

    @FXML
    AnchorPane whatsappTabPage;
    @FXML
    WhatsAppTabController whatsappTabPageController;

    @FXML
    AnchorPane deviceTabPage;
    @FXML
    DeviceTabController deviceTabPageController;

    @FXML
    AnchorPane mirrorTabPage;
    @FXML
    MirrorTabController mirrorTabPageController;

    @FXML
    Label statusTotalFiles;
    @FXML
    Label statusBackupSize;
    @FXML
    Label statusEncryption;
    @FXML
    Label statusBackupPath;

    @FXML
    public void initialize() {
        this.lockedTabPages = Arrays.asList(this.filesTabPage, this.mediaTabPage, this.appsTabPage, this.fileSearchTabPage, this.whatsappTabPage);

        this.tabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab == null) return;

            Node tabPage = newTab.getContent();

            if (oldTab != null) {
                Node oldTabPage = oldTab.getContent();
                if (oldTabPage == this.mirrorTabPage) {
                    this.mirrorTabPageController.tabDeselected();
                }
            }

            if (tabPage == this.deviceTabPage) return;

            if (tabPage == this.mirrorTabPage) {
                this.mirrorTabPageController.tabSelected();
                return;
            }

            if (this.selectedBackup == null) return;

            if (this.lockedTabPages.contains(tabPage) && !this.tryUnlock()) {
                if (oldTab != null) this.tabPane.getSelectionModel().select(oldTab);
            } else if (tabPage == this.filesTabPage) {
                this.filesTabPageController.tabShown(this.selectedBackup);
            } else if (tabPage == this.mediaTabPage) {
                this.mediaTabPageController.tabShown(this.selectedBackup);
            } else if (tabPage == this.appsTabPage) {
                this.appsTabPageController.tabShown(this.selectedBackup);
            } else if (tabPage == this.fileSearchTabPage) {
                this.fileSearchTabPageController.tabShown(this.selectedBackup);
            } else if (tabPage == this.whatsappTabPage) {
                this.whatsappTabPageController.tabShown(this.selectedBackup);
            }
        });

        this.tabPane.setVisible(false);
        this.tabPane.setManaged(false);
        this.welcomePane.setVisible(true);
        this.welcomePane.setManaged(true);
        this.welcomePane.setMouseTransparent(false);

        this.loadBackups();
        this.setupKeyboardShortcuts();
        this.setupDragAndDrop();

        Platform.runLater(this::applyTheme);
    }

    private void setupKeyboardShortcuts() {
        Platform.runLater(() -> {
            Scene scene = tabPane.getScene();
            if (scene != null) {
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN), this::fileOpenBackup);
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> tabPane.getSelectionModel().select(2));
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN), this::quit);
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::loadBackups);
            }
        });
    }

    private void setupDragAndDrop() {
        backupSidebarBox.setOnDragOver(event -> {
            if (event.getGestureSource() != backupSidebarBox && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        backupSidebarBox.setOnDragEntered(event -> {
            if (event.getGestureSource() != backupSidebarBox && event.getDragboard().hasFiles()) {
                backupSidebarBox.getStyleClass().add("drag-over");
            }
            event.consume();
        });

        backupSidebarBox.setOnDragExited(event -> {
            backupSidebarBox.getStyleClass().remove("drag-over");
            event.consume();
        });

        backupSidebarBox.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    if (file.isDirectory()) {
                        openBackup(file);
                        success = true;
                    } else if (file.getName().equals("Manifest.plist") || file.getName().equals("Manifest.db")) {
                        openBackup(file.getParentFile());
                        success = true;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void openBackup(File backupDirectory) {
        try {
            ITunesBackup backup = new ITunesBackup(backupDirectory);
            this.loadBackup(backup);
            this.selectBackup(backup);
            File parentDir = backupDirectory.getParentFile();
            if (parentDir != null) {
                PreferencesController.addBackupRoot(parentDir.getAbsolutePath());
            }
        } catch (FileNotFoundException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, "The following file was not found: " + e.getMessage());
        } catch (BackupReadException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
        }
    }

    public void cleanUp() {
        this.deviceTabPageController.stopPolling();
        this.mirrorTabPageController.stopAll();
        this.backups.forEach(ITunesBackup::cleanUp);
        ThumbnailService.getInstance().shutdown();
    }

    public void loadBackup(ITunesBackup backup) {
        String normalizedPath = backup.directory.toPath().toAbsolutePath().normalize().toString();
        if (this.backups.stream().anyMatch(existing ->
                existing.directory.toPath().toAbsolutePath().normalize().toString().equals(normalizedPath))) {
            return;
        }

        ToggleButton backupEntry = new ToggleButton(backup.manifest.deviceName + "\n" + BACKUP_DATE_FMT.format(
                backup.getBackupInfo().map(info -> info.lastBackupDate).orElse(backup.manifest.date).toInstant()));
        backupEntry.getStyleClass().add("sidebar-button");
        backupEntry.setOnAction(this::backupSelected);
        backupEntry.setMaxWidth(Integer.MAX_VALUE);
        backupEntry.setPrefHeight(60);
        backupEntry.setAlignment(Pos.BASELINE_LEFT);
        backupEntry.setPadding(new Insets(0, 24, 0, 24));
        backupEntry.setId(backup.directory.getName());

        MenuItem openBackupDirectory = new MenuItem("Open backup directory");
        openBackupDirectory.setOnAction(event -> {
            try {
                Desktop.getDesktop().browse(backup.directory.toURI());
            } catch (IOException e) {
                logger.error("Failed to open backup directory", e);
            }
        });

        MenuItem closeBackup = new MenuItem("Close backup");
        closeBackup.setOnAction(event -> {
            backup.cleanUp();
            if (this.selectedBackup == backup) {
                this.selectedBackup = null;
            }
            this.backups.remove(backup);
            this.backupSidebarBox.getChildren().remove(backupEntry);
            this.sidebarButtons.remove(backup);

            if (this.backups.isEmpty()) {
                showWelcome();
            } else if (this.selectedBackup == null) {
                selectBackup(this.backups.get(0));
            }
            updateStatusBar();
        });

        ContextMenu backupContextMenu = new ContextMenu(openBackupDirectory, closeBackup);
        backupEntry.setContextMenu(backupContextMenu);

        this.backups.add(backup);
        this.sidebarButtons.put(backup, backupEntry);
        this.backups.sort(Comparator.comparing((ITunesBackup b) -> b.manifest.date).reversed());

        List<Node> sorted = this.backups.stream()
                .map(b -> (Node) this.sidebarButtons.get(b))
                .filter(Objects::nonNull)
                .toList();
        this.backupSidebarBox.getChildren().setAll(sorted);
    }

    public void loadBackups() {
        ITunesBackup previousSelected = this.selectedBackup;

        this.backupSidebarBox.getChildren().clear();
        this.sidebarButtons.clear();
        this.backups.forEach(ITunesBackup::cleanUp);
        this.backups.clear();

        for (String root : PreferencesController.getBackupRoots()) {
            ITunesBackup.getBackups(new File(root)).forEach(this::loadBackup);
        }

        this.backups.sort(Comparator.comparing((ITunesBackup b) -> b.manifest.date).reversed());

        if (this.backups.isEmpty()) {
            this.selectedBackup = null;
            showWelcome();
            updateStatusBar();
            return;
        }

        Optional<ITunesBackup> byPrevious = previousSelected == null ? Optional.empty() :
                this.backups.stream().filter(backup -> backup.directory.equals(previousSelected.directory)).findFirst();

        if (byPrevious.isPresent()) {
            selectBackup(byPrevious.get());
        } else if (PreferencesController.getAutoSelectNewestBackup()) {
            selectBackup(this.backups.get(0));
        } else {
            this.selectedBackup = null;
            showWelcome();
            updateStatusBar();
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean tryUnlock() {
        if (this.selectedBackup == null) return false;
        if (!this.selectedBackup.isLocked()) return true;
        if (this.selectedBackup.manifest.getKeyBag().isEmpty()) return false;

        Optional<char[]> response = Dialogs.askPassword();
        if (response.isEmpty()) return false;
        char[] password = response.get();

        try {
            selectedBackup.manifest.getKeyBag().get().unlock(password);
            selectedBackup.decryptDatabase();
            updateStatusBar();
            return true;
        } catch (InvalidKeyException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, "The given password is not valid");
        } catch (BackupReadException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, "The backup could not be read");
        } catch (UnsupportedCryptoException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, "Your system doesn't support the necessary cryptography");
        } catch (NotUnlockedException e) {
            logger.error("Backup not unlocked", e);
        } catch (IOException e) {
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
        }
        return false;
    }

    public void selectBackup(ITunesBackup backup) {
        ToggleButton selectedButton = this.sidebarButtons.get(backup);
        if (selectedButton == null) return;
        selectedButton.setSelected(true);
        if (backup == this.selectedBackup) return;

        if (selectedBackup != null && sidebarButtons.get(selectedBackup) != null) {
            sidebarButtons.get(selectedBackup).setSelected(false);
        }
        this.selectedBackup = backup;

        showTabs();

        this.infoTabPageController.updateInformation(backup.manifest, backup.getBackupInfo().orElse(null));
        this.updateStatusBar();

        Node selectedTabPage = this.tabPane.getSelectionModel().getSelectedItem().getContent();
        if (this.lockedTabPages.contains(selectedTabPage) && !this.tryUnlock()) {
            this.tabPane.getSelectionModel().select(0);
        } else if (selectedTabPage == this.filesTabPage) {
            this.filesTabPageController.tabShown(backup);
        } else if (selectedTabPage == this.mediaTabPage) {
            this.mediaTabPageController.tabShown(backup);
        } else if (selectedTabPage == this.appsTabPage) {
            this.appsTabPageController.tabShown(backup);
        } else if (selectedTabPage == this.fileSearchTabPage) {
            this.fileSearchTabPageController.tabShown(backup);
        } else if (selectedTabPage == this.whatsappTabPage) {
            this.whatsappTabPageController.tabShown(backup);
        }

        if (!mediaToolsSetupOffered && MediaConverter.isMediaToolsNeeded()) {
            mediaToolsSetupOffered = true;
            Platform.runLater(this::offerMediaToolsSetup);
        }
    }

    private void showWelcome() {
        this.tabPane.setVisible(false);
        this.tabPane.setManaged(false);
        this.welcomePane.setVisible(true);
        this.welcomePane.setManaged(true);
        this.welcomePane.setMouseTransparent(false);
    }

    private void showTabs() {
        this.tabPane.setVisible(true);
        this.tabPane.setManaged(true);
        this.welcomePane.setVisible(false);
        this.welcomePane.setManaged(false);
        this.welcomePane.setMouseTransparent(true);
    }

    private void applyTheme() {
        Scene scene = tabPane.getScene();
        if (scene == null) return;

        Parent root = scene.getRoot();
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        root.getStyleClass().add(theme);
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
            controller.preferencesChangedCallback = this::applyTheme;

            Stage prefsWindow = new Stage();
            prefsWindow.initModality(Modality.APPLICATION_MODAL);
            prefsWindow.initOwner(tabPane.getScene().getWindow());

            Scene prefsScene = new Scene(root, 760, 620);
            prefsWindow.setScene(prefsScene);
            prefsWindow.setTitle("Preferences");
            prefsWindow.getIcons().add(ITunesBackupExplorer.APP_ICON);
            prefsWindow.show();
        } catch (IOException e) {
            logger.error("Failed to load preferences", e);
            Dialogs.showAlert(Alert.AlertType.ERROR, e.getMessage());
        }
    }

    @FXML
    public void fileOpenBackup() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iTunes Backup", "Manifest.plist", "Manifest.db"));
        File source = chooser.showOpenDialog(tabPane.getScene().getWindow());
        if (source == null) return;
        openBackup(source.getParentFile());
    }

    @FXML
    public void createBackup() {
        if (!DeviceService.isBackupToolAvailable()) {
            showPymobiledevice3Setup();
            return;
        }

        startBackupFlow();
    }

    private void showPymobiledevice3Setup() {
        Stage setupStage = new Stage();
        setupStage.initModality(Modality.APPLICATION_MODAL);
        setupStage.initOwner(tabPane.getScene().getWindow());
        setupStage.setTitle("Setting up...");
        setupStage.getIcons().add(ITunesBackupExplorer.APP_ICON);

        Label titleLabel = new Label("Installing required components");
        titleLabel.getStyleClass().add("section-title");

        Label descLabel = new Label("Installing pymobiledevice3 for device communication.\nThis is a one-time setup.");
        descLabel.setWrapText(true);

        ProgressBar progressBar = new ProgressBar(-1);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("mirror-setup-log");
        logArea.setPrefHeight(250);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Button closeButton = new Button("Cancel");
        closeButton.setOnAction(e -> setupStage.close());

        VBox layout = new VBox(10, titleLabel, descLabel, progressBar, logArea, closeButton);
        layout.setPadding(new Insets(16));
        layout.setAlignment(Pos.TOP_LEFT);

        Scene scene = new Scene(layout, 520, 400);
        scene.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm());

        Parent root = scene.getRoot();
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        root.getStyleClass().add(theme);

        setupStage.setScene(scene);
        setupStage.show();

        DeviceService.setupPymobiledevice3(
                line -> logArea.appendText(line + "\n"),
                () -> {
                    setupStage.close();
                    if (DeviceService.isPymobiledevice3Available()) {
                        startBackupFlow();
                    } else {
                        Dialogs.showAlert(Alert.AlertType.ERROR, "Setup completed but pymobiledevice3 was not detected. Check the log for errors.");
                    }
                },
                error -> {
                    progressBar.setProgress(0);
                    titleLabel.setText("Setup failed");
                    logArea.appendText("\nERROR: " + error + "\n");
                    closeButton.setText("Close");
                }
        );
    }

    private void offerMediaToolsSetup() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Media Tools");
        confirm.setHeaderText("Download media tools?");
        confirm.setContentText(
                "To display thumbnails for videos and HEIC photos, the app needs to download " +
                "ffmpeg and ImageMagick (~135 MB).\n\nDownload now?");

        DialogPane dp = confirm.getDialogPane();
        dp.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm());
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        dp.getStyleClass().add(theme);
        ((Stage) dp.getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                showMediaToolsSetup();
            }
        });
    }

    private void showMediaToolsSetup() {
        Stage setupStage = new Stage();
        setupStage.initModality(Modality.APPLICATION_MODAL);
        setupStage.initOwner(tabPane.getScene().getWindow());
        setupStage.setTitle("Setting up...");
        setupStage.getIcons().add(ITunesBackupExplorer.APP_ICON);

        Label titleLabel = new Label("Downloading media tools");
        titleLabel.getStyleClass().add("section-title");

        Label descLabel = new Label("Downloading ffmpeg and ImageMagick for video and HEIC thumbnail support.\nThis is a one-time setup.");
        descLabel.setWrapText(true);

        ProgressBar progressBar = new ProgressBar(-1);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("mirror-setup-log");
        logArea.setPrefHeight(250);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Button closeButton = new Button("Cancel");
        closeButton.setOnAction(e -> setupStage.close());

        VBox layout = new VBox(10, titleLabel, descLabel, progressBar, logArea, closeButton);
        layout.setPadding(new Insets(16));
        layout.setAlignment(Pos.TOP_LEFT);

        Scene scene = new Scene(layout, 520, 400);
        scene.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm());

        Parent root = scene.getRoot();
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        root.getStyleClass().add(theme);

        setupStage.setScene(scene);
        setupStage.show();

        MediaConverter.setupMediaTools(
                line -> logArea.appendText(line + "\n"),
                () -> {
                    setupStage.close();
                    if (MediaConverter.isFfmpegAvailable() && MediaConverter.isImageMagickAvailable()) {
                        Dialogs.showAlert(Alert.AlertType.INFORMATION, "Media tools installed successfully. Video and HEIC thumbnails are now available.");
                    } else if (MediaConverter.isFfmpegAvailable()) {
                        Dialogs.showAlert(Alert.AlertType.INFORMATION, "ffmpeg installed. Video thumbnails are available.\nImageMagick could not be installed — HEIC support may be limited.");
                    } else {
                        Dialogs.showAlert(Alert.AlertType.ERROR,
                                "Media tools could not be installed. Check the log for errors.");
                    }
                },
                error -> {
                    progressBar.setProgress(0);
                    titleLabel.setText("Setup failed");
                    logArea.appendText("\nERROR: " + error + "\n");
                    closeButton.setText("Close");
                }
        );
    }

    private void startBackupFlow() {
        Optional<String> device = DeviceService.detectDevice();
        if (device.isEmpty()) {
            String msg = "No device detected.\n\n";
            if (DeviceService.isWindows()) {
                msg += "Make sure that:\n"
                     + "• Apple Devices (or iTunes) is installed\n"
                     + "• The iPhone is connected via USB and unlocked\n"
                     + "• You tapped \"Trust\" on the device when prompted\n\n"
                     + "Download Apple Devices from the Microsoft Store if not installed.";
            } else {
                msg += "Make sure that:\n"
                     + "• The iPhone is connected via USB and unlocked\n"
                     + "• You tapped \"Trust\" on the device when prompted";
            }
            Dialogs.showAlert(Alert.AlertType.ERROR, msg);
            return;
        }

        String udid = device.get();

        long estimatedTotalBytes = 0;
        Optional<DeviceInfo> deviceInfo = DeviceService.getDeviceInfo(udid);
        if (deviceInfo.isPresent()) {
            estimatedTotalBytes = deviceInfo.get().estimatedBackupSize();
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select backup destination");
        File destination = chooser.showDialog(tabPane.getScene().getWindow());
        if (destination == null) return;

        Optional<Dialogs.EncryptionChoice> encryptionChoiceOpt = Dialogs.askBackupEncryption();
        if (encryptionChoiceOpt.isEmpty()) return;
        Dialogs.EncryptionChoice encryptionChoice = encryptionChoiceOpt.get();

        Stage progressStage = new Stage();
        progressStage.initModality(Modality.APPLICATION_MODAL);
        progressStage.initOwner(tabPane.getScene().getWindow());
        progressStage.setTitle("Creating Backup...");
        progressStage.getIcons().add(ITunesBackupExplorer.APP_ICON);
        progressStage.setMinWidth(520);
        progressStage.setMinHeight(400);

        Label titleLabel = new Label("Backup in progress");
        titleLabel.getStyleClass().add("section-title");

        String deviceName = deviceInfo.map(DeviceInfo::deviceName).orElse(udid);
        Label deviceLabel = new Label("Device: " + deviceName + "  (" + udid + ")");
        deviceLabel.getStyleClass().add("info-label");

        Label destLabel = new Label("Destination: " + destination.getAbsolutePath());
        destLabel.getStyleClass().add("info-label");
        destLabel.setWrapText(true);

        Label statusLabel = new Label("Starting backup...");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        Label percentLabel = new Label("0%");
        percentLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        percentLabel.setMinWidth(60);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        HBox progressRow = new HBox(10, progressBar, percentLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label filesLabel = new Label("Files received: 0");
        filesLabel.getStyleClass().add("info-label");

        Label transferredLabel = new Label("Transferred: 0 B" +
                (estimatedTotalBytes > 0 ? " / ~" + formatSize(estimatedTotalBytes) : ""));
        transferredLabel.getStyleClass().add("info-label");

        Label speedLabel = new Label("Transfer speed: --");
        speedLabel.getStyleClass().add("info-label");

        Label etaLabel = new Label("Estimated time remaining: calculating...");
        etaLabel.getStyleClass().add("info-label");

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("mirror-setup-log");
        logArea.setPrefHeight(200);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        Button cancelButton = new Button("Cancel");

        VBox layout = new VBox(10,
                titleLabel,
                deviceLabel,
                destLabel,
                new Separator(),
                progressRow,
                statusLabel,
                filesLabel,
                transferredLabel,
                speedLabel,
                etaLabel,
                new Separator(),
                logArea,
                cancelButton
        );
        layout.setPadding(new Insets(16));
        layout.setAlignment(Pos.TOP_LEFT);

        Scene scene = new Scene(layout, 560, 520);
        scene.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm());

        Parent root = scene.getRoot();
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        root.getStyleClass().add(theme);

        progressStage.setScene(scene);

        Pattern ideviceProgressPattern = Pattern.compile("^\\[.*\\]\\s+\\d");
        Pattern ideviceSizePattern = Pattern.compile("\\(([\\d.]+)\\s*(\\w+)/([\\d.]+)\\s*(\\w+)\\)");
        Pattern tqdmPattern = Pattern.compile("(\\d+)%\\|.*\\|\\s*([\\d.]+)/([\\d.]+)\\s*\\[([^<]*)<([^,]*),\\s*(.+)]");
        Pattern tqdmSimplePattern = Pattern.compile("(\\d+)%[|\\s]");
        Pattern idevicePctPattern = Pattern.compile("(\\d+)\\s*%");
        int[] fileCount = {0};
        int[] lastLoggedPct = {-1};
        long[] lastUiUpdate = {0};
        long[] startTime = {System.currentTimeMillis()};
        double[] accumulatedBytes = {0};
        double[] prevFileTotal = {0};
        long finalEstimatedTotalBytes = estimatedTotalBytes;
        boolean[] cliSizeDataAvailable = {false};
        long[] prevCliTransferred = {0};
        long[] prevCliTime = {0};
        double[] cliSmoothedSpeed = {0};
        String[] currentProgressFile = {""};

        javafx.concurrent.Task<DeviceService.BackupResult> task = new javafx.concurrent.Task<>() {
            @Override
            protected DeviceService.BackupResult call() {
                Consumer<String> onProgressLine = line -> {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) return;

                    Matcher tqdmMatcher = tqdmPattern.matcher(trimmed);
                    if (tqdmMatcher.find()) {
                        cliSizeDataAvailable[0] = true;
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate[0] < 400) return;
                        lastUiUpdate[0] = now;

                        int pct = Integer.parseInt(tqdmMatcher.group(1));
                        double pctFloat = Double.parseDouble(tqdmMatcher.group(2));
                        String elapsed = tqdmMatcher.group(4).trim();
                        String remaining = tqdmMatcher.group(5).trim();

                        String transferredText;
                        String speedText;
                        if (finalEstimatedTotalBytes > 0) {
                            long transferred = (long) (pctFloat / 100.0 * finalEstimatedTotalBytes);
                            transferredText = "Transferred: " + formatSize(transferred) + " / ~" + formatSize(finalEstimatedTotalBytes);
                            if (prevCliTime[0] > 0 && transferred > prevCliTransferred[0]) {
                                long timeDelta = now - prevCliTime[0];
                                long sizeDelta = transferred - prevCliTransferred[0];
                                if (timeDelta > 0) {
                                    double instantSpeed = sizeDelta / (timeDelta / 1000.0);
                                    cliSmoothedSpeed[0] = cliSmoothedSpeed[0] == 0
                                            ? instantSpeed
                                            : 0.3 * instantSpeed + 0.7 * cliSmoothedSpeed[0];
                                }
                            }
                            prevCliTransferred[0] = transferred;
                            prevCliTime[0] = now;
                            speedText = cliSmoothedSpeed[0] > 0
                                    ? "Transfer speed: " + formatSpeed(cliSmoothedSpeed[0])
                                    : "Transfer speed: calculating...";
                        } else {
                            transferredText = "Progress: " + String.format(java.util.Locale.ROOT, "%.1f%%", pctFloat);
                            long elapsedMs = now - startTime[0];
                            if (elapsedMs > 3000 && pctFloat > 0) {
                                double pctPerSec = pctFloat / (elapsedMs / 1000.0);
                                speedText = "Transfer speed: " + String.format(java.util.Locale.ROOT, "%.1f%%/min", pctPerSec * 60);
                            } else {
                                speedText = "Transfer speed: calculating...";
                            }
                        }

                        int logPct = pct / 5 * 5;
                        boolean shouldLog = logPct > lastLoggedPct[0] && logPct % 5 == 0;
                        if (shouldLog) lastLoggedPct[0] = logPct;
                        String logLine = null;
                        if (shouldLog) {
                            if (finalEstimatedTotalBytes > 0) {
                                long transferred = (long) (pctFloat / 100.0 * finalEstimatedTotalBytes);
                                logLine = pct + "% completed — " + formatSize(transferred) + " transferred (" + elapsed + " elapsed)\n";
                            } else {
                                logLine = pct + "% completed (" + elapsed + " elapsed)\n";
                            }
                        }
                        String logEntry = logLine;

                        Platform.runLater(() -> {
                            progressBar.setProgress(pct / 100.0);
                            percentLabel.setText(pct + "%");
                            statusLabel.setText("Backup in progress...");
                            speedLabel.setText(speedText);
                            etaLabel.setText("Estimated time remaining: " + remaining);
                            transferredLabel.setText(transferredText);
                            filesLabel.setText("Elapsed: " + elapsed);
                            if (logEntry != null) logArea.appendText(logEntry);
                        });
                        return;
                    }

                    Matcher tqdmSimpleMatcher = tqdmSimplePattern.matcher(trimmed);
                    if (tqdmSimpleMatcher.find()) {
                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate[0] < 400) return;
                        lastUiUpdate[0] = now;

                        int pct = Integer.parseInt(tqdmSimpleMatcher.group(1));
                        Platform.runLater(() -> {
                            progressBar.setProgress(pct / 100.0);
                            percentLabel.setText(pct + "%");
                            statusLabel.setText("Backup in progress...");
                        });
                        return;
                    }

                    boolean isIdeviceProgress = ideviceProgressPattern.matcher(trimmed).find();

                    if (isIdeviceProgress) {
                        int bracketEnd = trimmed.indexOf(']');
                        if (bracketEnd > 1) {
                            String filePath = trimmed.substring(1, bracketEnd);
                            if (!filePath.equals(currentProgressFile[0])) {
                                currentProgressFile[0] = filePath;
                                fileCount[0]++;
                            }
                        }
                    }

                    if (!isIdeviceProgress) {
                        long elapsedSec = (System.currentTimeMillis() - startTime[0]) / 1000;
                        Platform.runLater(() -> {
                            logArea.appendText(trimmed + "\n");
                            filesLabel.setText("Files received: " + fileCount[0] + "  |  Elapsed: " + formatDuration(elapsedSec));
                        });
                    }

                    Matcher sm = ideviceSizePattern.matcher(trimmed);
                    boolean hasSizeInfo = sm.find();

                    if (hasSizeInfo) {
                        cliSizeDataAvailable[0] = true;
                        long now = System.currentTimeMillis();

                        double fileCurrent = toBytes(Double.parseDouble(sm.group(1)), sm.group(2));
                        double fileTotal = toBytes(Double.parseDouble(sm.group(3)), sm.group(4));

                        if (fileTotal != prevFileTotal[0] && prevFileTotal[0] > 0) {
                            accumulatedBytes[0] += prevFileTotal[0];
                        }
                        prevFileTotal[0] = fileTotal;

                        if (now - lastUiUpdate[0] < 500) return;
                        lastUiUpdate[0] = now;

                        double totalTransferred = accumulatedBytes[0] + fileCurrent;

                        if (prevCliTime[0] > 0 && totalTransferred > prevCliTransferred[0]) {
                            long timeDelta = now - prevCliTime[0];
                            long sizeDelta = (long) (totalTransferred - prevCliTransferred[0]);
                            if (timeDelta > 0 && sizeDelta > 0) {
                                double instantSpeed = sizeDelta / (timeDelta / 1000.0);
                                cliSmoothedSpeed[0] = cliSmoothedSpeed[0] == 0
                                        ? instantSpeed
                                        : 0.3 * instantSpeed + 0.7 * cliSmoothedSpeed[0];
                            }
                        }
                        prevCliTransferred[0] = (long) totalTransferred;
                        prevCliTime[0] = now;

                        String speedText = "Transfer speed: --";
                        String etaText = "Estimated time remaining: calculating...";
                        double overallPct = 0;

                        if (cliSmoothedSpeed[0] > 0) {
                            speedText = "Transfer speed: " + formatSpeed(cliSmoothedSpeed[0]);

                            if (finalEstimatedTotalBytes > 0) {
                                overallPct = (totalTransferred / finalEstimatedTotalBytes) * 100.0;
                                if (overallPct > 100) overallPct = 99.9;
                                double remainingBytes = finalEstimatedTotalBytes - totalTransferred;
                                if (remainingBytes > 0) {
                                    long remainingSec = (long) (remainingBytes / cliSmoothedSpeed[0]);
                                    etaText = "Estimated time remaining: ~" + formatDuration(remainingSec);
                                } else {
                                    etaText = "Estimated time remaining: finishing...";
                                }
                            }
                        }

                        String transferredText = "Transferred: " + formatSize((long) totalTransferred) +
                                (finalEstimatedTotalBytes > 0 ? " / ~" + formatSize(finalEstimatedTotalBytes) : "");
                        String statusText = "Current file: " + sm.group(1) + " " + sm.group(2) +
                                " / " + sm.group(3) + " " + sm.group(4);

                        int logPct = (int) (overallPct / 5) * 5;
                        boolean shouldLog = logPct > lastLoggedPct[0] && logPct % 5 == 0 && overallPct > 0;
                        if (shouldLog) lastLoggedPct[0] = logPct;
                        String logEntry = null;
                        if (shouldLog) {
                            long elapsed = (now - startTime[0]) / 1000;
                            logEntry = logPct + "% completed — " + formatSize((long) totalTransferred) + " transferred (" + formatDuration(elapsed) + " elapsed)\n";
                        }

                        double pctFinal = overallPct;
                        String speedFinal = speedText;
                        String etaFinal = etaText;
                        String transferredFinal = transferredText;
                        long elapsedSec = (now - startTime[0]) / 1000;
                        String logEntryFinal = logEntry;

                        Platform.runLater(() -> {
                            statusLabel.setText(statusText);
                            transferredLabel.setText(transferredFinal);
                            speedLabel.setText(speedFinal);
                            etaLabel.setText(etaFinal);
                            filesLabel.setText("Files received: " + fileCount[0] + "  |  Elapsed: " + formatDuration(elapsedSec));
                            if (finalEstimatedTotalBytes > 0) {
                                progressBar.setProgress(pctFinal / 100.0);
                                percentLabel.setText(String.format("%.1f%%", pctFinal));
                            } else {
                                progressBar.setProgress(-1);
                                percentLabel.setText(formatSize((long) (accumulatedBytes[0] + fileCurrent)));
                            }
                            if (logEntryFinal != null) logArea.appendText(logEntryFinal);
                        });
                    } else if (isIdeviceProgress) {
                        Matcher pctMatcher = idevicePctPattern.matcher(trimmed);
                        if (pctMatcher.find()) {
                            int pct = Integer.parseInt(pctMatcher.group(1));
                            long now = System.currentTimeMillis();
                            if (now - lastUiUpdate[0] < 500) return;
                            lastUiUpdate[0] = now;

                            long elapsedSec = (now - startTime[0]) / 1000;
                            Platform.runLater(() -> {
                                statusLabel.setText("Current file: " + pct + "% complete");
                                filesLabel.setText("Files received: " + fileCount[0] + "  |  Elapsed: " + formatDuration(elapsedSec));
                            });
                        }
                    }
                };

                if (encryptionChoice.enable()) {
                    onProgressLine.accept("[encryption] Enabling encrypted backup...");
                    boolean ok = DeviceService.setBackupEncryption(udid, encryptionChoice.password(), true, onProgressLine);
                    if (!ok) {
                        onProgressLine.accept("[encryption] Failed to enable encryption.");
                        clearPassword(encryptionChoice.password());
                        return DeviceService.BackupResult.FAILED;
                    }
                }

                DeviceService.BackupResult result = DeviceService.createBackup(
                        udid, destination, onProgressLine, this::isCancelled);

                if (encryptionChoice.enable() && encryptionChoice.disableAfter() && result != DeviceService.BackupResult.CANCELLED) {
                    onProgressLine.accept("[encryption] Disabling backup encryption...");
                    boolean offOk = DeviceService.setBackupEncryption(udid, encryptionChoice.password(), false, onProgressLine);
                    if (!offOk) {
                        onProgressLine.accept("[encryption] Warning: failed to disable encryption.");
                    }
                }

                clearPassword(encryptionChoice.password());
                return result;
            }
        };

        Runnable confirmCancel = () -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Cancel Backup");
            confirm.setHeaderText("Are you sure you want to cancel the backup?");
            confirm.setContentText("The backup in progress will be stopped and any partial data may be incomplete.");
            confirm.initOwner(progressStage);
            confirm.initModality(javafx.stage.Modality.WINDOW_MODAL);

            DialogPane dp = confirm.getDialogPane();
            dp.getStylesheets().add(
                    ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm());
            String confirmTheme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
            dp.getStyleClass().add(confirmTheme);

            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    task.cancel();
                    progressStage.close();
                }
            });
        };

        cancelButton.setOnAction(e -> confirmCancel.run());
        progressStage.setOnCloseRequest(e -> {
            e.consume();
            confirmCancel.run();
        });

        java.util.Timer[] sizeMonitor = {null};

        task.setOnSucceeded(event -> Platform.runLater(() -> {
            if (sizeMonitor[0] != null) sizeMonitor[0].cancel();
            DeviceService.BackupResult result = task.getValue();
            switch (result) {
                case SUCCESS -> {
                    progressStage.close();
                    PreferencesController.addBackupRoot(destination.getAbsolutePath());
                    loadBackups();
                    File backupDir = new File(destination, udid);
                    backups.stream()
                            .filter(b -> b.directory.equals(backupDir))
                            .findFirst()
                            .ifPresentOrElse(
                                    this::selectBackup,
                                    () -> openBackup(backupDir)
                            );
                }
                case CANCELLED -> {
                    progressStage.close();
                    Dialogs.showAlert(Alert.AlertType.INFORMATION, "Backup cancelled.");
                }
                case FAILED -> {
                    titleLabel.setText("Backup failed");
                    statusLabel.setText("The backup process exited with an error. Check the log below for details.");
                    progressBar.setProgress(0);
                    percentLabel.setText("--");
                    cancelButton.setText("Close");
                    cancelButton.setOnAction(e -> progressStage.close());
                }
            }
        }));

        task.setOnCancelled(event -> Platform.runLater(() -> {
            if (sizeMonitor[0] != null) sizeMonitor[0].cancel();
            if (progressStage.isShowing()) {
                progressStage.close();
            }
        }));

        task.setOnFailed(event -> Platform.runLater(() -> {
            if (sizeMonitor[0] != null) sizeMonitor[0].cancel();
            titleLabel.setText("Backup failed");
            String msg = task.getException() != null ? task.getException().getMessage() : "Unknown error";
            statusLabel.setText("Error: " + msg);
            logArea.appendText("\nERROR: " + msg + "\n");
            progressBar.setProgress(0);
            percentLabel.setText("--");
            cancelButton.setText("Close");
            cancelButton.setOnAction(e -> progressStage.close());
        }));

        Thread backupThread = new Thread(task);
        backupThread.setDaemon(true);
        backupThread.start();

        if (!DeviceService.isWindows()) {
            File backupSubDir = new File(destination, udid);
            long[] prevDirSize = {0};
            long[] prevMeasureTime = {0};
            double[] smoothedSpeed = {0};
            sizeMonitor[0] = new java.util.Timer("backup-size-monitor", true);
            sizeMonitor[0].schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    if (cliSizeDataAvailable[0]) return;
                    if (!backupSubDir.exists()) return;

                    long dirSize = calculateDirectorySize(backupSubDir.toPath());
                    if (dirSize <= 0) return;

                    long now = System.currentTimeMillis();

                    if (prevMeasureTime[0] > 0) {
                        long timeDelta = now - prevMeasureTime[0];
                        long sizeDelta = dirSize - prevDirSize[0];
                        if (timeDelta > 0 && sizeDelta > 0) {
                            double instantSpeed = sizeDelta / (timeDelta / 1000.0);
                            smoothedSpeed[0] = smoothedSpeed[0] == 0
                                    ? instantSpeed
                                    : 0.3 * instantSpeed + 0.7 * smoothedSpeed[0];
                        }
                    }
                    prevDirSize[0] = dirSize;
                    prevMeasureTime[0] = now;

                    String speedText = "Transfer speed: --";
                    String etaText = "Estimated time remaining: calculating...";
                    double pct = 0;

                    String transferredText = "Transferred: " + formatSize(dirSize) +
                            (finalEstimatedTotalBytes > 0 ? " / ~" + formatSize(finalEstimatedTotalBytes) : "");

                    if (smoothedSpeed[0] > 0) {
                        speedText = "Transfer speed: " + formatSpeed(smoothedSpeed[0]);

                        if (finalEstimatedTotalBytes > 0) {
                            pct = (dirSize * 100.0) / finalEstimatedTotalBytes;
                            if (pct > 100) pct = 99.9;
                            double remainingBytes = finalEstimatedTotalBytes - dirSize;
                            if (remainingBytes > 0) {
                                long remainingSec = (long) (remainingBytes / smoothedSpeed[0]);
                                etaText = "Estimated time remaining: ~" + formatDuration(remainingSec);
                            } else {
                                etaText = "Estimated time remaining: finishing...";
                            }
                        }
                    }

                    int logPct = (int) (pct / 5) * 5;
                    boolean shouldLog = logPct > lastLoggedPct[0] && logPct % 5 == 0 && pct > 0;
                    if (shouldLog) lastLoggedPct[0] = logPct;
                    String logEntry = null;
                    if (shouldLog) {
                        long elapsed = (now - startTime[0]) / 1000;
                        logEntry = logPct + "% completed — " + formatSize(dirSize) + " transferred (" + formatDuration(elapsed) + " elapsed)\n";
                    }

                    double pctFinal = pct;
                    String speedFinal = speedText;
                    String etaFinal = etaText;
                    String transferredFinal = transferredText;
                    String logEntryFinal = logEntry;
                    long elapsedSec = (now - startTime[0]) / 1000;

                    Platform.runLater(() -> {
                        transferredLabel.setText(transferredFinal);
                        speedLabel.setText(speedFinal);
                        etaLabel.setText(etaFinal);
                        filesLabel.setText("Files received: " + fileCount[0] + "  |  Elapsed: " + formatDuration(elapsedSec));
                        if (finalEstimatedTotalBytes > 0 && pctFinal > 0) {
                            progressBar.setProgress(pctFinal / 100.0);
                            percentLabel.setText(String.format("%.1f%%", pctFinal));
                        } else {
                            progressBar.setProgress(-1);
                            percentLabel.setText(formatSize(dirSize));
                        }
                        if (logEntryFinal != null) logArea.appendText(logEntryFinal);
                    });
                }
            }, 3000, 3000);
        }

        progressStage.show();
    }

    @FXML
    public void reloadBackupsAction() {
        this.loadBackups();
    }

    @FXML
    public void quit() {
        this.cleanUp();
        Platform.exit();
    }

    @FXML
    public void helpAbout() {
        Alert about = new Alert(Alert.AlertType.INFORMATION);
        about.setTitle("About iTunes Backup Explorer");
        about.setHeaderText("iTunes Backup Explorer v2.1");
        about.setContentText(
                "Originally developed by MaxiHuHe04\n" +
                        "Enhanced by fredac100\n\n" +
                        "License: MIT\n\n" +
                        "https://github.com/MaxiHuHe04/iTunes-Backup-Explorer\n" +
                        "https://github.com/fredac100"
        );

        DialogPane dialogPane = about.getDialogPane();
        dialogPane.setMinWidth(480);
        dialogPane.getStylesheets().add(
                ITunesBackupExplorer.class.getResource("stylesheet.css").toExternalForm()
        );
        String theme = "Light".equalsIgnoreCase(PreferencesController.getTheme()) ? "theme-light" : "theme-dark";
        dialogPane.getStyleClass().add(theme);

        ((Stage) dialogPane.getScene().getWindow()).getIcons().add(ITunesBackupExplorer.APP_ICON);
        about.showAndWait();
    }

    private static double toBytes(double value, String unit) {
        return switch (unit.toUpperCase()) {
            case "KB" -> value * 1024;
            case "MB" -> value * 1024 * 1024;
            case "GB" -> value * 1024 * 1024 * 1024;
            case "TB" -> value * 1024 * 1024 * 1024 * 1024;
            default -> value;
        };
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f GB/s", bytesPerSec / (1024 * 1024 * 1024));
        if (bytesPerSec >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024 * 1024));
        if (bytesPerSec >= 1024) return String.format(java.util.Locale.ROOT, "%.1f KB/s", bytesPerSec / 1024);
        return String.format(java.util.Locale.ROOT, "%.0f B/s", bytesPerSec);
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) return "--";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %02ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    private static long calculateDirectorySize(java.nio.file.Path dir) {
        if (!java.nio.file.Files.isDirectory(dir)) return 0;
        try (var stream = java.nio.file.Files.walk(dir)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                         .mapToLong(p -> {
                             try { return java.nio.file.Files.size(p); }
                             catch (Exception e) { return 0; }
                         }).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateStatusBar() {
        if (selectedBackup == null) {
            statusTotalFiles.setText("Total files: --");
            statusBackupSize.setText("Size: --");
            statusEncryption.setText("Encryption: --");
            statusBackupPath.setText("Backup: --");
            return;
        }

        String encryptionStatus = selectedBackup.manifest.encrypted ?
                (selectedBackup.isLocked() ? "Encrypted (locked)" : "Encrypted (unlocked)") : "Not encrypted";
        statusEncryption.setText("Encryption: " + encryptionStatus);
        statusBackupPath.setText("Backup: " + selectedBackup.directory.getAbsolutePath());

        if (selectedBackup.isLocked()) {
            statusTotalFiles.setText("Total files: --");
            statusBackupSize.setText("Size: --");
            return;
        }

        ITunesBackup backup = selectedBackup;
        javafx.concurrent.Task<long[]> task = new javafx.concurrent.Task<>() {
            @Override
            protected long[] call() throws Exception {
                return backup.queryFileStats();
            }
        };

        task.setOnSucceeded(event -> {
            long[] result = task.getValue();
            Platform.runLater(() -> {
                statusTotalFiles.setText("Total files: " + result[0]);
                if (result[1] >= 0) {
                    statusBackupSize.setText("Size: " + FileSize.format(result[1]));
                } else {
                    statusBackupSize.setText("Size: --");
                }
            });
        });

        task.setOnFailed(event -> Platform.runLater(() -> {
            statusTotalFiles.setText("Total files: --");
            statusBackupSize.setText("Size: --");
        }));

        new Thread(task).start();
    }

    private static void clearPassword(char[] password) {
        if (password == null) return;
        Arrays.fill(password, '\0');
    }
}
