package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import me.maxih.itunes_backup_explorer.util.UtilDict;

import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Optional;

public class BackupManifest {
    public final boolean encrypted;
    public final String version;
    public final Date date;
    public final NSData manifestKey;
    public final boolean passcodeSet;

    public final String productVersion;
    public final String productType;
    public final String buildVersion;
    public final String uniqueDeviceID;
    public final String serialNumber;
    public final String deviceName;

    public final NSDictionary applications;
    private KeyBag keyBag;

    public BackupManifest(NSDictionary data) throws BackupReadException {
        UtilDict dict = new UtilDict(data);
        try {
            UtilDict lockdown = dict.getDict("Lockdown").orElseThrow();

            this.encrypted = dict.getBoolean("IsEncrypted").orElseThrow();
            this.version = dict.getString("Version").orElseThrow();
            this.date = dict.getDate("Date").orElseThrow();
            this.manifestKey = dict.getData("ManifestKey").orElseThrow();
            this.passcodeSet = dict.getBoolean("WasPasscodeSet").orElseThrow();
            this.productVersion = lockdown.getString("ProductVersion").orElseThrow();
            this.productType = lockdown.getString("ProductType").orElseThrow();
            this.buildVersion = lockdown.getString("BuildVersion").orElseThrow();
            this.uniqueDeviceID = lockdown.getString("UniqueDeviceID").orElseThrow();
            this.serialNumber = lockdown.getString("SerialNumber").orElseThrow();
            this.deviceName = lockdown.getString("DeviceName").orElseThrow();
            this.applications = dict.get(NSDictionary.class, "Applications").orElseThrow();
            if (this.encrypted) {
                this.keyBag = new KeyBag(dict.getData("BackupKeyBag").orElseThrow());
            }
        } catch (NoSuchElementException e) {
            throw new BackupReadException(e);
        }
    }

    public Optional<KeyBag> getKeyBag() {
        return Optional.ofNullable(this.keyBag);
    }
}
