package me.maxih.itunes_backup_explorer.util;

import java.util.Locale;

public class FileSize {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    public static String format(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp >= UNITS.length) exp = UNITS.length - 1;
        return String.format(Locale.ROOT, "%.1f %s", bytes / Math.pow(1024, exp), UNITS[exp]);
    }
}
