package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSDictionary;
import me.maxih.itunes_backup_explorer.util.UtilDict;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Optional;

public class BackupManifest {
    public final boolean encrypted;
    public final String version;
    public final Date date;
    public final boolean passcodeSet;

    public final String productVersion;
    public final String productType;
    public final String buildVersion;
    public final String uniqueDeviceID;
    public final String serialNumber;
    public final String deviceName;

    public final NSDictionary applications;

    public final int protectionClass;

    private final byte[] manifestKey;
    private final KeyBag keyBag;

    public BackupManifest(NSDictionary data) throws BackupReadException {
        UtilDict dict = new UtilDict(data);
        try {
            UtilDict lockdown = dict.getDict("Lockdown").orElseThrow();

            this.encrypted = dict.getBoolean("IsEncrypted").orElseThrow();
            this.version = dict.getString("Version").orElseThrow();
            this.date = dict.getDate("Date").orElseThrow();
            this.passcodeSet = dict.getBoolean("WasPasscodeSet").orElseThrow();
            this.productVersion = lockdown.getString("ProductVersion").orElseThrow();
            this.productType = lockdown.getString("ProductType").orElseThrow();
            this.buildVersion = lockdown.getString("BuildVersion").orElseThrow();
            this.uniqueDeviceID = lockdown.getString("UniqueDeviceID").orElseThrow();
            this.serialNumber = lockdown.getString("SerialNumber").orElseThrow();
            this.deviceName = lockdown.getString("DeviceName").orElseThrow();
            this.applications = dict.get(NSDictionary.class, "Applications").orElseThrow();
            if (this.encrypted) {
                byte[] keyData = dict.getData("ManifestKey").orElseThrow().bytes();
                ByteBuffer keyBuffer = ByteBuffer.wrap(keyData);

                this.protectionClass = keyBuffer.order(ByteOrder.LITTLE_ENDIAN).getInt();

                this.manifestKey = new byte[keyData.length - 4];
                keyBuffer.order(ByteOrder.BIG_ENDIAN).get(manifestKey);

                this.keyBag = new KeyBag(dict.getData("BackupKeyBag").orElseThrow());
            } else {
                this.protectionClass = 0;
                this.manifestKey = null;
                this.keyBag = null;
            }
        } catch (NoSuchElementException e) {
            throw new BackupReadException(e);
        }
    }

    public Optional<byte[]> getManifestKey() {
        return Optional.ofNullable(this.manifestKey);
    }

    public Optional<KeyBag> getKeyBag() {
        return Optional.ofNullable(this.keyBag);
    }
}
