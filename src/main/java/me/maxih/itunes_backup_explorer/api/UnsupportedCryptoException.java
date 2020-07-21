package me.maxih.itunes_backup_explorer.api;

public class UnsupportedCryptoException extends Exception {

    public UnsupportedCryptoException(Throwable cause) {
        super("This system does not support necessary cryptography", cause);
    }

}
