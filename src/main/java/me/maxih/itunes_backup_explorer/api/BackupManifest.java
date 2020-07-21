package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSData;
import com.dd.plist.NSDate;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;

import java.util.Date;
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
        try {
            NSDictionary lockdown = (NSDictionary) data.objectForKey("Lockdown");

            this.encrypted = ((NSNumber) data.objectForKey("IsEncrypted")).boolValue();
            this.version = data.objectForKey("Version").toString();
            this.date = ((NSDate) data.objectForKey("Date")).getDate();
            this.manifestKey = ((NSData) data.objectForKey("ManifestKey"));
            this.passcodeSet = ((NSNumber) data.objectForKey("WasPasscodeSet")).boolValue();
            this.productVersion = lockdown.objectForKey("ProductVersion").toString();
            this.productType = lockdown.objectForKey("ProductType").toString();
            this.buildVersion = lockdown.objectForKey("BuildVersion").toString();
            this.uniqueDeviceID = lockdown.objectForKey("UniqueDeviceID").toString();
            this.serialNumber = lockdown.objectForKey("SerialNumber").toString();
            this.deviceName = lockdown.objectForKey("DeviceName").toString();
            this.applications = (NSDictionary) data.objectForKey("Applications");
            if (this.encrypted) {
                this.keyBag = new KeyBag((NSData) data.objectForKey("BackupKeyBag"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new BackupReadException(e);
        }
    }

    public Optional<KeyBag> getKeyBag() {
        return Optional.ofNullable(this.keyBag);
    }
}
