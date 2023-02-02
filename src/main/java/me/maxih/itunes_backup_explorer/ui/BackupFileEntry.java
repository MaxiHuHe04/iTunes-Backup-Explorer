package me.maxih.itunes_backup_explorer.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.scene.image.Image;
import me.maxih.itunes_backup_explorer.ITunesBackupExplorer;
import me.maxih.itunes_backup_explorer.api.BackupFile;
import me.maxih.itunes_backup_explorer.util.BackupPathUtils;

import java.io.ByteArrayInputStream;
import java.util.Optional;

public class BackupFileEntry {
    private static final Image domainGroupIcon = ITunesBackupExplorer.getIcon("domain_group.png");
    private static final Image folderIcon = ITunesBackupExplorer.getIcon("folder.png");
    private static final Image fileIcon = ITunesBackupExplorer.getIcon("file.png");

    private BackupFile file;
    private final String title;
    private final int pathLevel;
    private final Image icon;
    private final BooleanProperty checkBoxSelected = new SimpleBooleanProperty(false);
    private final BooleanProperty checkBoxIndeterminate = new SimpleBooleanProperty(false);
    private final ObjectProperty<Selection> selection = new SimpleObjectProperty<>(Selection.NONE);

    {
        this.selection.bind(Bindings.createObjectBinding(() -> {
            if (checkBoxIndeterminate.get()) return Selection.PARTIAL;
            else if (checkBoxSelected.get()) return Selection.ALL;
            else return Selection.NONE;
        }, checkBoxSelected, checkBoxIndeterminate));
    }

    public BackupFileEntry(String title) {
        this.title = title;
        this.pathLevel = 0;
        this.icon = domainGroupIcon;
    }

    public BackupFileEntry(BackupFile file) {
        this.file = file;
        this.title = file.getFileName().equals("") ? file.domain : file.getFileName();
        this.pathLevel = BackupPathUtils.getPathLevel(file.relativePath);

        String appID = file.domain.startsWith("AppDomain-") ? file.domain.substring("AppDomain-".length()) : null;
        if (file.relativePath.equals("") && appID != null && file.backup.getBackupInfo().map(info -> info.applications.containsKey(appID)).orElse(false)) {
            byte[] imageData = file.backup.getBackupInfo().get().applications.get(appID).placeholderIcon.bytes();
            ByteArrayInputStream imageStream = new ByteArrayInputStream(imageData);
            this.icon = new Image(imageStream);
        } else {
            this.icon = file.getFileType() == BackupFile.FileType.DIRECTORY ? folderIcon : fileIcon;
        }
    }

    public Optional<BackupFile> getFile() {
        return Optional.ofNullable(this.file);
    }

    public String getDisplayName() {
        return this.title;
    }

    public String getFileName() {
        return this.getFile().map(BackupFile::getFileName).orElse(this.title);
    }

    public String getDomain() {
        return this.file != null ? this.file.domain : null;
    }

    public long getSize() {
        return this.file != null ? this.file.getSize() : 0;
    }

    public int getPathLevel() {
        return this.pathLevel;
    }

    public String getRelativePath() {
        return this.getFile().map(f -> f.relativePath).orElse(this.title);
    }

    public String getParentPath() {
        return this.getFile().map(BackupFile::getParentPath).orElse("");
    }

    public Image getIcon() {
        return this.icon;
    }

    public BooleanProperty checkBoxSelectedProperty() {
        return checkBoxSelected;
    }

    public BooleanProperty checkBoxIndeterminateProperty() {
        return checkBoxIndeterminate;
    }

    public Selection getSelection() {
        return selection.get();
    }

    public void setSelection(Selection selection) {
        switch (selection) {
            case NONE:
                this.checkBoxSelected.set(false);
                this.checkBoxIndeterminate.set(false);
                break;
            case PARTIAL:
                this.checkBoxIndeterminate.set(true);
                break;
            case ALL:
                this.checkBoxSelected.set(true);
                this.checkBoxIndeterminate.set(false);
                break;
        }
    }

    public ReadOnlyObjectProperty<Selection> selectionProperty() {
        return selection;
    }

    public enum Selection {
        NONE,
        PARTIAL,
        ALL
    }
}
