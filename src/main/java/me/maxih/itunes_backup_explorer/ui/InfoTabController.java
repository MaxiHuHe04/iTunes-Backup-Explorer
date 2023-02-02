package me.maxih.itunes_backup_explorer.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import me.maxih.itunes_backup_explorer.api.BackupInfo;
import me.maxih.itunes_backup_explorer.api.BackupManifest;

public class InfoTabController {

    @FXML
    private Label infoDeviceName;
    @FXML
    private TextField infoSerialNumber;
    @FXML
    private TextField infoUUID;
    @FXML
    private TextField infoPhoneNumber;
    @FXML
    private Label infoProductType;
    @FXML
    private Label infoProductVersion;
    @FXML
    private Label infoBuildVersion;
    @FXML
    private Label infoEncrypted;
    @FXML
    private Label infoVersion;
    @FXML
    private Label infoCreationDate;
    @FXML
    private Label infoLastDate;

    public void updateInformation(BackupManifest manifest, BackupInfo info) {
        this.infoDeviceName.setText(manifest.deviceName);
        this.infoSerialNumber.setText(manifest.serialNumber);
        this.infoUUID.setText(manifest.uniqueDeviceID);
        this.infoProductType.setText(manifest.productType);
        this.infoProductVersion.setText(manifest.productVersion);
        this.infoBuildVersion.setText(manifest.buildVersion);
        this.infoEncrypted.setText(manifest.encrypted ? "yes" : "no");
        this.infoVersion.setText(manifest.version);
        this.infoCreationDate.setText(manifest.date.toString());
        if (info == null) {
            this.infoPhoneNumber.setText("");
            this.infoLastDate.setText("Unknown");
        } else {
            this.infoPhoneNumber.setText(info.phoneNumber == null ? "" : info.phoneNumber);
            this.infoLastDate.setText(info.lastBackupDate.toString());
        }
    }

}
