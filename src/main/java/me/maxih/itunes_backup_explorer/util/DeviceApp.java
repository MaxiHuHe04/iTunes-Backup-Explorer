package me.maxih.itunes_backup_explorer.util;

public record DeviceApp(
        String name,
        String bundleId,
        String version,
        String appType
) {}
