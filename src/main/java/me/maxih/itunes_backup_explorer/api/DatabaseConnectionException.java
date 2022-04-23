package me.maxih.itunes_backup_explorer.api;

public class DatabaseConnectionException extends Exception {

    public DatabaseConnectionException() {
        super("Database connection failed");
    }

    public DatabaseConnectionException(Throwable cause) {
        this();
        initCause(cause);
    }

}
