package me.maxih.itunes_backup_explorer.api;

public class BackupReadException extends Exception {

    public BackupReadException(String message, Throwable cause) {
        super(message, cause);
    }

    public BackupReadException(Throwable cause) {
        super(cause);
    }

    public BackupReadException(String message) {
        super(message);
    }

}
