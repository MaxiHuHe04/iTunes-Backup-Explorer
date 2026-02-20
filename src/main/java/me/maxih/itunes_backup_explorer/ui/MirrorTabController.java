package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import me.maxih.itunes_backup_explorer.util.DeviceService;
import me.maxih.itunes_backup_explorer.util.MirrorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MirrorTabController {
    private static final Logger logger = LoggerFactory.getLogger(MirrorTabController.class);

    @FXML private VBox setupPane;
    @FXML private Button setupButton;
    @FXML private TextArea setupLog;
    @FXML private VBox noDevicePane;
    @FXML private VBox developerModePane;
    @FXML private Label developerModeDescription;
    @FXML private Label developerModeHint;
    @FXML private Button retryButton;
    @FXML private VBox tunnelPane;
    @FXML private TextField tunnelCommandField;
    @FXML private Button startTunnelButton;
    @FXML private Button retryTunnelButton;
    @FXML private Label tunnelStatusLabel;
    @FXML private VBox viewPane;
    @FXML private Label stateIndicator;
    @FXML private Label fpsLabel;
    @FXML private Label wdaStatusLabel;
    @FXML private Button airPlayButton;
    @FXML private Button startStopButton;
    @FXML private StackPane screenContainer;
    @FXML private ProgressIndicator connectingSpinner;
    @FXML private ImageView screenView;
    @FXML private Label statusLabel;

    private final MirrorService mirrorService = new MirrorService();
    private ScheduledExecutorService pollingExecutor;
    private boolean deviceConnected = false;
    private String currentUdid = null;
    private boolean streaming = false;
    private boolean tabActive = false;
    private int frameCount = 0;
    private long fpsWindowStart = 0;
    private double pressX, pressY;

    @FXML
    private void initialize() {
        if (!mirrorService.isVenvReady()) {
            showPane("setup");
        } else {
            showPane("noDevice");
            startPolling();
        }

        screenView.fitWidthProperty().bind(screenContainer.widthProperty());
        screenView.fitHeightProperty().bind(screenContainer.heightProperty());

        screenView.setCursor(Cursor.HAND);
        screenView.setOnMousePressed(this::onScreenPressed);
        screenView.setOnMouseReleased(this::onScreenReleased);
        screenView.setOnScroll(this::onScrollEvent);
    }

    private void startPolling() {
        if (pollingExecutor != null) return;
        pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mirror-device-poll");
            t.setDaemon(true);
            return t;
        });
        pollingExecutor.scheduleWithFixedDelay(this::pollDevice, 0, 3, TimeUnit.SECONDS);
    }

    public void tabSelected() {
        tabActive = true;
        if (deviceConnected && mirrorService.isVenvReady() && !streaming) {
            checkAndStartStreaming();
        }
    }

    public void tabDeselected() {
        tabActive = false;
        if (streaming) {
            stopStreaming();
        }
    }

    public void stopAll() {
        stopStreaming();
        if (pollingExecutor != null) pollingExecutor.shutdownNow();
        mirrorService.shutdown();
    }

    @FXML
    private void onSetupClicked() {
        setupButton.setDisable(true);
        setupLog.setVisible(true);
        setupLog.setManaged(true);
        setupLog.clear();
        mirrorService.setup(
            line -> Platform.runLater(() -> setupLog.appendText(line + "\n")),
            () -> Platform.runLater(() -> {
                showPane("noDevice");
                startPolling();
            }),
            msg -> Platform.runLater(() -> {
                setupLog.appendText("ERROR: " + msg);
                setupButton.setDisable(false);
            })
        );
    }

    @FXML
    private void onStartStopClicked() {
        if (streaming) {
            stopStreaming();
        } else if (deviceConnected) {
            checkAndStartStreaming();
        }
    }

    @FXML
    private void onAirPlayClicked() {
        if (streaming) return;
        if (!mirrorService.isUxplayAvailable()) {
            Optional<ButtonType> result = Dialogs.showAlert(
                    Alert.AlertType.INFORMATION,
                    "AirPlay requires uxplay-windows to be installed.\n\n"
                            + "Click OK to open the download page.\n"
                            + "After installing, click AirPlay again.",
                    ButtonType.OK, ButtonType.CANCEL
            );
            if (result.isPresent() && result.get() == ButtonType.OK) {
                mirrorService.openUxplayDownloadPage();
            }
            return;
        }
        streaming = true;
        airPlayButton.setDisable(true);
        showPane("view");
        startStopButton.setText("Stop");
        fpsWindowStart = System.currentTimeMillis();
        frameCount = 0;
        mirrorService.startAirPlay(this::applyState, this::updateFrame);
    }

    @FXML
    private void onRevealDeveloperMode() {
        if (currentUdid == null) return;
        developerModeHint.setText("Sending command to iPhone...");
        Thread thread = new Thread(() -> {
            mirrorService.revealDeveloperMode(currentUdid);
            Platform.runLater(() -> developerModeHint.setText(
                    "Command sent. Check Settings > Privacy & Security on the iPhone."
            ));
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onRetryConnection() {
        if (currentUdid == null) return;
        retryButton.setDisable(true);
        retryTunnelButton.setDisable(true);
        checkAndStartStreaming();
    }

    @FXML
    private void onStartTunnel() {
        if (currentUdid == null) return;
        startTunnelButton.setDisable(true);
        tunnelStatusLabel.setText("Starting tunnel...");
        retryTunnelButton.setDisable(true);
        startStreamingWithTunnel();
    }

    private void checkAndStartStreaming() {
        if (currentUdid == null) return;
        String udid = currentUdid;

        Thread checkThread = new Thread(() -> {
            MirrorService.DeviceReadiness readiness = mirrorService.checkDeviceReadiness(udid);
            Platform.runLater(() -> {
                retryButton.setDisable(false);
                retryTunnelButton.setDisable(false);
                switch (readiness) {
                    case DEVELOPER_MODE_DISABLED -> showPane("developerMode");
                    case NEEDS_TUNNEL -> startStreamingWithTunnel();
                    case READY, CHECK_FAILED -> startStreaming();
                }
            });
        });
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private void startStreamingWithTunnel() {
        if (currentUdid == null) return;
        streaming = true;
        showPane("view");
        startStopButton.setText("Stop");
        fpsWindowStart = System.currentTimeMillis();
        frameCount = 0;
        mirrorService.ensureTunnelAndStart(currentUdid, this::applyState, this::updateFrame);
    }

    private void startStreaming() {
        if (currentUdid == null) return;
        streaming = true;
        showPane("view");
        startStopButton.setText("Stop");
        fpsWindowStart = System.currentTimeMillis();
        frameCount = 0;
        mirrorService.start(currentUdid, this::applyState, this::updateFrame);
    }

    private void stopStreaming() {
        mirrorService.stop();
        streaming = false;
        Platform.runLater(() -> {
            airPlayButton.setDisable(false);
            startStopButton.setText("Start");
            connectingSpinner.setVisible(false);
            screenView.setImage(null);
            fpsLabel.setText("");
            if (!deviceConnected) {
                showPane("noDevice");
            }
        });
    }

    private void applyState(MirrorService.State state) {
        switch (state) {
            case CONNECTING -> {
                stateIndicator.setText("Connecting");
                stateIndicator.getStyleClass().removeIf(c -> c.startsWith("mirror-state-"));
                stateIndicator.getStyleClass().addAll("mirror-state-badge", "mirror-state-connecting");
                connectingSpinner.setVisible(true);
                screenView.setVisible(false);
                statusLabel.setVisible(false);
                statusLabel.setManaged(false);
            }
            case VIEW_ONLY -> {
                stateIndicator.setText("View Only");
                stateIndicator.getStyleClass().removeIf(c -> c.startsWith("mirror-state-"));
                stateIndicator.getStyleClass().addAll("mirror-state-badge", "mirror-state-view-only");
                connectingSpinner.setVisible(false);
                screenView.setVisible(true);
                wdaStatusLabel.setText("Touch not available");
                screenView.setCursor(Cursor.DEFAULT);
            }
            case INTERACTIVE -> {
                stateIndicator.setText("Interactive");
                stateIndicator.getStyleClass().removeIf(c -> c.startsWith("mirror-state-"));
                stateIndicator.getStyleClass().addAll("mirror-state-badge", "mirror-state-interactive");
                wdaStatusLabel.setText("Touch active");
                screenView.setCursor(Cursor.HAND);
            }
            case ERROR -> {
                String msg = mirrorService.getErrorMessage();
                if ("TUNNEL_REQUIRED".equals(msg)) {
                    streaming = false;
                    startStreamingWithTunnel();
                    return;
                }
                stateIndicator.setText("Error");
                stateIndicator.getStyleClass().removeIf(c -> c.startsWith("mirror-state-"));
                stateIndicator.getStyleClass().addAll("mirror-state-badge", "mirror-state-error");
                statusLabel.setText(msg.isEmpty() ? "Device connection error" : msg);
                statusLabel.setVisible(true);
                statusLabel.setManaged(true);
                connectingSpinner.setVisible(false);
            }
            case DISCONNECTED -> {
            }
        }
    }

    private void updateFrame(byte[] pngBytes) {
        Image image = new Image(new ByteArrayInputStream(pngBytes));
        screenView.setImage(image);
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - fpsWindowStart >= 1000) {
            fpsLabel.setText(frameCount + " FPS");
            frameCount = 0;
            fpsWindowStart = now;
        }
    }

    private void pollDevice() {
        Optional<String> udid = DeviceService.detectDevice();
        if (udid.isPresent() && !deviceConnected) {
            deviceConnected = true;
            currentUdid = udid.get();
            Platform.runLater(() -> {
                if (tabActive && !streaming) {
                    checkAndStartStreaming();
                } else if (!streaming) {
                    showPane("view");
                }
            });
        } else if (udid.isEmpty() && deviceConnected) {
            deviceConnected = false;
            currentUdid = null;
            if (streaming) stopStreaming();
            Platform.runLater(() -> showPane("noDevice"));
        }
    }

    private void onScreenPressed(MouseEvent event) {
        pressX = event.getX();
        pressY = event.getY();
    }

    private void onScreenReleased(MouseEvent event) {
        double releaseX = event.getX();
        double releaseY = event.getY();
        double dx = releaseX - pressX;
        double dy = releaseY - pressY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double[] fromNorm = toNormalized(pressX, pressY);
        if (distance < 10) {
            mirrorService.sendTap(fromNorm[0], fromNorm[1]);
        } else {
            double[] toNorm = toNormalized(releaseX, releaseY);
            mirrorService.sendSwipe(fromNorm[0], fromNorm[1], toNorm[0], toNorm[1], 300);
        }
    }

    private double[] toNormalized(double mouseX, double mouseY) {
        Image image = screenView.getImage();
        if (image == null) return new double[]{0, 0};
        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        double viewWidth = screenView.getBoundsInLocal().getWidth();
        double viewHeight = screenView.getBoundsInLocal().getHeight();
        double scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
        double displayedWidth = imageWidth * scale;
        double displayedHeight = imageHeight * scale;
        double offsetX = (viewWidth - displayedWidth) / 2;
        double offsetY = (viewHeight - displayedHeight) / 2;
        double normX = (mouseX - offsetX) / displayedWidth;
        double normY = (mouseY - offsetY) / displayedHeight;
        normX = Math.max(0, Math.min(1, normX));
        normY = Math.max(0, Math.min(1, normY));
        return new double[]{normX, normY};
    }

    private void onScrollEvent(ScrollEvent event) {
    }

    private void showPane(String which) {
        setupPane.setVisible("setup".equals(which));
        setupPane.setManaged("setup".equals(which));
        noDevicePane.setVisible("noDevice".equals(which));
        noDevicePane.setManaged("noDevice".equals(which));
        developerModePane.setVisible("developerMode".equals(which));
        developerModePane.setManaged("developerMode".equals(which));
        tunnelPane.setVisible("tunnel".equals(which));
        tunnelPane.setManaged("tunnel".equals(which));
        viewPane.setVisible("view".equals(which));
        viewPane.setManaged("view".equals(which));
    }
}
