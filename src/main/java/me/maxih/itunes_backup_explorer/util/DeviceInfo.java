package me.maxih.itunes_backup_explorer.util;

public record DeviceInfo(
        String deviceName,
        String modelNumber,
        String productType,
        String productVersion,
        String buildVersion,
        String serialNumber,
        String udid,
        String phoneNumber,
        String wifiMac,
        int batteryLevel,
        String batteryStatus,
        long totalDiskCapacity,
        long totalDataCapacity,
        long totalDataAvailable,
        long totalMobileAppUsage,
        long webAppCacheUsage,
        long mediaCacheUsage
) {
    public long estimatedBackupSize() {
        if (totalDataCapacity <= 0 || totalDataAvailable < 0) return 0;
        long usedSpace = totalDataCapacity - totalDataAvailable;
        if (usedSpace <= 0) return 0;

        if (totalMobileAppUsage > 0) {
            long appBinaries = (long) (totalMobileAppUsage * 0.75);
            long caches = Math.max(0, webAppCacheUsage) + Math.max(0, mediaCacheUsage);
            long systemOverhead = Math.min(10L * 1024 * 1024 * 1024, (long) (usedSpace * 0.10));
            long estimate = usedSpace - appBinaries - caches - systemOverhead;
            long minEstimate = (long) (usedSpace * 0.10);
            long maxEstimate = (long) (usedSpace * 0.80);
            return Math.max(minEstimate, Math.min(maxEstimate, estimate));
        }

        return (long) (usedSpace * 0.40);
    }
}
