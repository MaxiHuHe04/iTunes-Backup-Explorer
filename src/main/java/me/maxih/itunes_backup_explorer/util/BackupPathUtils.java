package me.maxih.itunes_backup_explorer.util;

import java.util.regex.Pattern;

public class BackupPathUtils {

    public static final char SEPARATOR = '/';
    private static final Pattern INVALID_CHARACTERS = Pattern.compile("[:*?\"<>|]");

    public static String getParentPath(String path) {
        int lastSep = path.lastIndexOf(SEPARATOR);
        if (lastSep == -1) return "";
        return path.substring(0, lastSep);
    }

    public static String getFileName(String path) {
        int lastSep = path.lastIndexOf(SEPARATOR);
        if (lastSep == -1) return path;
        else return path.substring(lastSep + 1);
    }

    public static int getPathLevel(String path) {
        if (path.length() == 0) {
            return 0;
        } else {
            int level = 1;
            for (char c : path.toCharArray())
                if (c == SEPARATOR) level++;
            return level;
        }
    }

    public static String getFileExtension(String path) {
        String name = getFileName(path);
        int lastSep = name.lastIndexOf('.');
        if (lastSep == -1) return "";
        else return name.substring(lastSep + 1);
    }

    public static String cleanPath(String path) {
        return INVALID_CHARACTERS.matcher(path).replaceAll("-");
    }

    private BackupPathUtils() {
    }

}
