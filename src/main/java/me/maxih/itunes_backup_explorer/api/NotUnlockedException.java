package me.maxih.itunes_backup_explorer.api;

public class NotUnlockedException extends Exception {

    public NotUnlockedException() {
        super("Key bag was never unlocked");
    }

}
