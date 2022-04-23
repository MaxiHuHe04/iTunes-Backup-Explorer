package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import me.maxih.itunes_backup_explorer.util.UtilDict;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

public class BackupInfo {
    public final Map<String, ApplicationInfo> applications;
    public final String[] installedApplications;
    public final Date lastBackupDate;
    public final String buildVersion;
    public final String deviceName;
    public final String displayName;
    public final String productName;
    public final String productType;
    public final String productVersion;
    public final String serialNumber;

    // Fields below can be null
    public final String phoneNumber;
    public final String guid;
    public final String iccid;
    public final String imei;
    public final String imei2;
    public final String meid;
    public final String targetIdentifier;
    public final String targetType;
    public final String uniqueIdentifier;

    public BackupInfo(NSDictionary data) throws BackupReadException {
        UtilDict dict = new UtilDict(data);
        try {
            this.applications = new HashMap<>();
            dict.getDict("Applications").orElseThrow().forTypedEntries(NSDictionary.class, (key, value) -> {
                UtilDict info = new UtilDict(value);
                ApplicationInfo app = new ApplicationInfo(
                        info.getData("PlaceholderIcon").orElseThrow(),
                        info.getData("iTunesMetadata").orElse(null),
                        info.getBoolean("IsDemotedApp").orElse(false),
                        info.getData("ApplicationSINF").orElse(null)
                );
                this.applications.put(key, app);
            });
            this.installedApplications = dict.getTypedArrayStream(NSString.class, "Installed Applications")
                    .orElse(Stream.empty())
                    .map(NSString::getContent)
                    .toArray(String[]::new);
            this.lastBackupDate = dict.getDate("Last Backup Date").orElseThrow();
            this.buildVersion = dict.getString("Build Version").orElseThrow();
            this.deviceName = dict.getString("Device Name").orElseThrow();
            this.displayName = dict.getString("Display Name").orElseThrow();
            this.productName = dict.getString("Product Name").orElseThrow();
            this.productType = dict.getString("Product Type").orElseThrow();
            this.productVersion = dict.getString("Product Version").orElseThrow();
            this.serialNumber = dict.getString("Serial Number").orElseThrow();

            this.phoneNumber = dict.getString("Phone Number").orElse(null);
            this.guid = dict.getString("GUID").orElse(null);
            this.iccid = dict.getString("ICCID").orElse(null);
            this.imei = dict.getString("IMEI").orElse(null);
            this.imei2 = dict.getString("IMEI2").orElse(null);
            this.meid = dict.getString("MEID").orElse(null);
            this.targetIdentifier = dict.getString("Target Identifier").orElse(null);
            this.targetType = dict.getString("Target Type").orElse(null);
            this.uniqueIdentifier = dict.getString("Unique Identifier").orElse(null);
        } catch (NoSuchElementException e) {
            throw new BackupReadException(e);
        }
    }

    public static class ApplicationInfo {
        public final NSData placeholderIcon;
        public final NSData iTunesMetadata;
        public final boolean isDemotedApp;
        public final NSData applicationSINF;

        public ApplicationInfo(NSData placeholderIcon, NSData iTunesMetadata, Boolean isDemotedApp, NSData applicationSINF) {
            this.placeholderIcon = placeholderIcon;
            this.iTunesMetadata = iTunesMetadata;
            this.isDemotedApp = isDemotedApp;
            this.applicationSINF = applicationSINF;
        }
    }
}
