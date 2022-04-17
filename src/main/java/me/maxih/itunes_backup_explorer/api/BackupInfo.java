package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.*;

import java.util.*;

public class BackupInfo {
    private static String getStringOrNull(NSDictionary dict, String key) {
        return dict.containsKey(key) ? dict.objectForKey(key).toString() : null;
    }

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
        try {
            this.applications = new HashMap<>();
            for (Map.Entry<String, NSObject> entry : ((NSDictionary) data.objectForKey("Applications")).entrySet()) {
                NSDictionary info = (NSDictionary) entry.getValue();
                boolean isDemotedApp = info.containsKey("IsDemotedApp") && ((NSNumber) info.objectForKey("IsDemotedApp")).boolValue();
                ApplicationInfo app = new ApplicationInfo((NSData) info.objectForKey("PlaceholderIcon"), (NSData) info.objectForKey("iTunesMetadata"), isDemotedApp, (NSData) info.objectForKey("ApplicationSINF"));
                this.applications.put(entry.getKey(), app);
            }
            this.installedApplications = Arrays.stream(((NSArray) data.objectForKey("Installed Applications")).getArray()).map(NSObject::toString).toArray(String[]::new);
            this.lastBackupDate = ((NSDate) data.objectForKey("Last Backup Date")).getDate();
            this.buildVersion = data.objectForKey("Build Version").toString();
            this.deviceName = data.objectForKey("Device Name").toString();
            this.displayName = data.objectForKey("Display Name").toString();
            this.productName = data.objectForKey("Product Name").toString();
            this.productType = data.objectForKey("Product Type").toString();
            this.productVersion = data.objectForKey("Product Version").toString();
            this.serialNumber = data.objectForKey("Serial Number").toString();

            this.phoneNumber = getStringOrNull(data, "Phone Number");
            this.guid = getStringOrNull(data, "GUID");
            this.iccid = getStringOrNull(data, "ICCID");
            this.imei = getStringOrNull(data, "IMEI");
            this.imei2 = getStringOrNull(data, "IMEI2");
            this.meid = getStringOrNull(data, "MEID");
            this.targetIdentifier = getStringOrNull(data, "Target Identifier");
            this.targetType = getStringOrNull(data, "Target Type");
            this.uniqueIdentifier = getStringOrNull(data, "Unique Identifier");
        } catch (Exception e) {
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
