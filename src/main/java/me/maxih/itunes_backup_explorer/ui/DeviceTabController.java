package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import me.maxih.itunes_backup_explorer.util.DeviceApp;
import me.maxih.itunes_backup_explorer.util.DeviceInfo;
import me.maxih.itunes_backup_explorer.util.DeviceService;
import me.maxih.itunes_backup_explorer.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeviceTabController {
    private static final Logger logger = LoggerFactory.getLogger(DeviceTabController.class);

    private ScheduledExecutorService pollingExecutor;
    private boolean deviceConnected = false;
    private String currentUdid = null;
    private final ObservableList<DeviceApp> appsList = FXCollections.observableArrayList();
    private FilteredList<DeviceApp> filteredApps;
    private String activeTypeFilter = null;

    @FXML
    private VBox noDevicePane;
    @FXML
    private ScrollPane deviceScrollPane;
    @FXML
    private Label libimobiledeviceWarning;
    @FXML
    private Label deviceNameLabel;
    @FXML
    private Label deviceModelLabel;
    @FXML
    private Label infoName;
    @FXML
    private Label infoModel;
    @FXML
    private Label infoiOS;
    @FXML
    private Label infoPhone;
    @FXML
    private Label infoWifiMac;
    @FXML
    private TextField infoSerial;
    @FXML
    private TextField infoUdid;
    @FXML
    private ProgressBar batteryBar;
    @FXML
    private ProgressBar storageBar;
    @FXML
    private Label batteryPercentLabel;
    @FXML
    private Label batteryStatusLabel;
    @FXML
    private Label storageInfoLabel;
    @FXML
    private Label appCountLabel;
    @FXML
    private TextField appSearchField;
    @FXML
    private TableView<DeviceApp> appsTable;
    @FXML
    private TableColumn<DeviceApp, String> nameColumn;
    @FXML
    private TableColumn<DeviceApp, String> bundleIdColumn;
    @FXML
    private TableColumn<DeviceApp, String> versionColumn;
    @FXML
    private TableColumn<DeviceApp, String> typeColumn;
    @FXML
    private ToggleButton filterAll;
    @FXML
    private ToggleButton filterUser;
    @FXML
    private ToggleButton filterSystem;
    @FXML
    private Button uninstallButton;

    @FXML
    private void initialize() {
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        bundleIdColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().bundleId()));
        versionColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().version()));
        typeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().appType()));

        filteredApps = new FilteredList<>(appsList, p -> true);
        appsTable.setItems(filteredApps);

        ToggleGroup typeFilterGroup = new ToggleGroup();
        filterAll.setToggleGroup(typeFilterGroup);
        filterUser.setToggleGroup(typeFilterGroup);
        filterSystem.setToggleGroup(typeFilterGroup);
        filterAll.setSelected(true);

        typeFilterGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                oldToggle.setSelected(true);
                return;
            }
            if (newToggle == filterAll) activeTypeFilter = null;
            else if (newToggle == filterUser) activeTypeFilter = "User";
            else if (newToggle == filterSystem) activeTypeFilter = "System";
            applyFilter();
        });

        appSearchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        appsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldApp, newApp) -> {
            uninstallButton.setDisable(newApp == null || !"User".equals(newApp.appType()));
        });

        if (!DeviceService.isLibimobiledeviceAvailable() && !DeviceService.isPymobiledevice3Available()) {
            libimobiledeviceWarning.setVisible(true);
            libimobiledeviceWarning.setManaged(true);
        }

        pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-polling");
            t.setDaemon(true);
            return t;
        });
        pollingExecutor.scheduleWithFixedDelay(this::pollDevice, 0, 3, TimeUnit.SECONDS);
    }

    private void pollDevice() {
        if (!DeviceService.isLibimobiledeviceAvailable() && !DeviceService.isPymobiledevice3Available()) {
            Platform.runLater(() -> {
                libimobiledeviceWarning.setVisible(true);
                libimobiledeviceWarning.setManaged(true);
            });
            return;
        }
        Optional<String> udid = DeviceService.detectDevice();
        if (udid.isPresent() && !deviceConnected) {
            currentUdid = udid.get();
            deviceConnected = true;
            Platform.runLater(this::showDevice);
            loadDeviceData();
        } else if (udid.isEmpty() && deviceConnected) {
            deviceConnected = false;
            currentUdid = null;
            Platform.runLater(this::showNoDevice);
        }
    }

    private void showDevice() {
        noDevicePane.setVisible(false);
        noDevicePane.setManaged(false);
        deviceScrollPane.setVisible(true);
        deviceScrollPane.setManaged(true);
    }

    private void showNoDevice() {
        noDevicePane.setVisible(true);
        noDevicePane.setManaged(true);
        deviceScrollPane.setVisible(false);
        deviceScrollPane.setManaged(false);
    }

    private void loadDeviceData() {
        String udid = this.currentUdid;
        javafx.concurrent.Task<Object[]> task = new javafx.concurrent.Task<>() {
            @Override
            protected Object[] call() {
                Optional<DeviceInfo> info = DeviceService.getDeviceInfo(udid);
                List<DeviceApp> userApps = DeviceService.getApps(udid, false);
                List<DeviceApp> systemApps = DeviceService.getApps(udid, true);
                List<DeviceApp> allApps = new ArrayList<>(userApps);
                allApps.addAll(systemApps);
                return new Object[]{info.orElse(null), allApps};
            }
        };
        task.setOnSucceeded(event -> {
            Object[] result = task.getValue();
            DeviceInfo info = (DeviceInfo) result[0];
            @SuppressWarnings("unchecked")
            List<DeviceApp> apps = (List<DeviceApp>) result[1];
            updateUI(info, apps);
        });
        task.setOnFailed(event -> logger.error("Failed to load device data", task.getException()));
        new Thread(task).start();
    }

    private void updateUI(DeviceInfo info, List<DeviceApp> apps) {
        if (info == null) {
            deviceNameLabel.setText("--");
            deviceModelLabel.setText("--");
            infoName.setText("--");
            infoModel.setText("--");
            infoiOS.setText("--");
            infoSerial.setText("--");
            infoUdid.setText("--");
            infoPhone.setText("--");
            infoWifiMac.setText("--");
            batteryBar.setProgress(0);
            batteryPercentLabel.setText("--");
            batteryStatusLabel.setText("--");
            storageBar.setProgress(0);
            storageInfoLabel.setText("--");
        } else {
            deviceNameLabel.setText(info.deviceName());
            deviceModelLabel.setText(info.productType());
            infoName.setText(info.deviceName());
            infoModel.setText(info.modelNumber() + " (" + info.productType() + ")");
            infoiOS.setText(info.productVersion() + " (" + info.buildVersion() + ")");
            infoSerial.setText(info.serialNumber());
            infoUdid.setText(info.udid());
            infoPhone.setText(info.phoneNumber().isEmpty() ? "--" : info.phoneNumber());
            infoWifiMac.setText(info.wifiMac().isEmpty() ? "--" : info.wifiMac());

            batteryBar.setProgress(info.batteryLevel() / 100.0);
            batteryPercentLabel.setText(info.batteryLevel() + "%");
            batteryStatusLabel.setText(info.batteryStatus());
            if (info.batteryLevel() <= 20) {
                if (!batteryBar.getStyleClass().contains("device-battery-bar-low")) {
                    batteryBar.getStyleClass().add("device-battery-bar-low");
                }
            } else {
                batteryBar.getStyleClass().remove("device-battery-bar-low");
            }

            if (info.totalDiskCapacity() > 0) {
                long used = info.totalDiskCapacity() - info.totalDataAvailable();
                double ratio = (double) used / info.totalDiskCapacity();
                storageBar.setProgress(ratio);
                storageInfoLabel.setText(FileSize.format(used) + " used of " + FileSize.format(info.totalDiskCapacity()));
            } else {
                storageBar.setProgress(0);
                storageInfoLabel.setText("--");
            }
        }

        appsList.setAll(apps);
        appCountLabel.setText(apps.size() + " apps");
    }

    private void applyFilter() {
        String search = appSearchField.getText();
        filteredApps.setPredicate(app -> {
            if (activeTypeFilter != null && !activeTypeFilter.equals(app.appType())) return false;
            if (search == null || search.isBlank()) return true;
            String filter = search.toLowerCase();
            return app.name().toLowerCase().contains(filter) || app.bundleId().toLowerCase().contains(filter);
        });
    }

    @FXML
    private void uninstallApp() {
        DeviceApp selected = appsTable.getSelectionModel().getSelectedItem();
        if (selected == null || !"User".equals(selected.appType())) return;

        Optional<ButtonType> result = Dialogs.showAlert(
                Alert.AlertType.CONFIRMATION,
                "Uninstall \"" + selected.name() + "\" (" + selected.bundleId() + ")?",
                ButtonType.OK, ButtonType.CANCEL
        );
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        String udid = this.currentUdid;
        String bundleId = selected.bundleId();
        javafx.concurrent.Task<Boolean> task = new javafx.concurrent.Task<>() {
            @Override
            protected Boolean call() {
                return DeviceService.uninstallApp(udid, bundleId);
            }
        };
        task.setOnSucceeded(e -> {
            if (task.getValue()) {
                appsList.remove(selected);
                appCountLabel.setText(appsList.size() + " apps");
                Dialogs.showAlert(Alert.AlertType.INFORMATION, "\"" + selected.name() + "\" uninstalled successfully");
            } else {
                Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to uninstall \"" + selected.name() + "\"");
            }
        });
        task.setOnFailed(e -> Dialogs.showAlert(Alert.AlertType.ERROR, "Failed to uninstall app"));
        new Thread(task).start();
    }

    public void stopPolling() {
        if (pollingExecutor != null) pollingExecutor.shutdownNow();
    }
}
