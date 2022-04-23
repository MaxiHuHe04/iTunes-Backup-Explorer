package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.*;
import me.maxih.itunes_backup_explorer.util.BackupPathUtils;
import me.maxih.itunes_backup_explorer.util.UtilDict;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.InvalidKeyException;
import java.util.NoSuchElementException;

public class BackupFile {
    public final ITunesBackup backup;
    public final UtilDict data;
    public final String fileID;
    public final String domain;
    public final String relativePath;
    public final int flags;

    private final FileType fileType;

    private final UtilDict properties;
    private final NSObject[] objects;

    private File contentFile;

    private long size;
    private int protectionClass;
    private byte[] encryptionKey;

    public BackupFile(ITunesBackup backup, String fileID, String domain, String relativePath, int flags, NSDictionary data) throws BackupReadException {
        this.backup = backup;
        this.fileID = fileID;
        this.domain = domain;
        this.relativePath = relativePath;
        this.flags = flags;
        this.data = new UtilDict(data);

        this.fileType = FileType.fromFlags(flags);

        try {
            this.objects = this.data.getArray("$objects").orElseThrow();
            this.properties = new UtilDict(this.getObject(NSDictionary.class, this.data.get(UID.class, "$top", "root").orElseThrow()));

            if (this.fileType == FileType.FILE) {
                this.contentFile = Paths.get(backup.directory.getAbsolutePath(), fileID.substring(0, 2), fileID).toFile();
                if (!this.contentFile.exists()) throw new BackupReadException("Missing file: " + this.fileID);

                this.size = this.properties.get(NSNumber.class, "Size").orElseThrow().longValue();
                this.protectionClass = this.properties.get(NSNumber.class, "ProtectionClass").orElseThrow().intValue();

                if (this.isEncrypted()) {
                    this.encryptionKey = new byte[40];
                    ByteBuffer encryptionKeyBuffer = ByteBuffer.wrap(this.encryptionKey);
                    new UtilDict(this.getObject(NSDictionary.class, this.properties.get(UID.class, "EncryptionKey").orElseThrow()))
                            .getData("NS.data")
                            .orElseThrow()
                            .getBytes(encryptionKeyBuffer, 4, 40);
                }
            }
        } catch (NoSuchElementException e) {
            throw new BackupReadException(e);
        }
    }

    private <T extends NSObject> T getObject(Class<T> type, UID uid) throws NoSuchElementException {
        byte index = uid.getBytes()[0];
        Object obj = this.objects[index];
        if (type.isInstance(obj)) return type.cast(obj);
        throw new NoSuchElementException();
    }


    public FileType getFileType() {
        return fileType;
    }

    public File getContentFile() {
        return contentFile;
    }

    public long getSize() {
        return size;
    }

    public boolean isEncrypted() {
        return this.protectionClass != 0;
    }

    public String getFileName() {
        return BackupPathUtils.getFileName(this.relativePath);
    }

    public String getFileExtension() {
        return BackupPathUtils.getFileExtension(this.relativePath);
    }

    public String getParentPath() {
        return BackupPathUtils.getParentPath(this.relativePath);
    }


    public void extract(File destination)
            throws IOException, BackupReadException, NotUnlockedException, UnsupportedCryptoException, UnsupportedOperationException {

        switch (this.fileType) {
            case DIRECTORY:
                if (!destination.exists()) Files.createDirectory(destination.toPath());
                break;
            case FILE:
                if (this.isEncrypted()) {
                    if (this.backup.manifest.getKeyBag().isEmpty())
                        throw new BackupReadException("Encrypted file in non-encrypted backup");

                    try {
                        this.backup.manifest.getKeyBag().get().decryptFile(this.protectionClass, this.encryptionKey, this.contentFile, destination);
                    } catch (InvalidKeyException e) {
                        throw new BackupReadException(e);
                    }
                } else {
                    Files.copy(this.contentFile.toPath(), destination.toPath());
                }
                break;
            case SYMBOLIC_LINK:
                throw new UnsupportedOperationException("Not implemented yet");
        }

    }

    public void extractToFolder(File destinationFolder, boolean withRelativePath)
            throws IOException, BackupReadException, NotUnlockedException, UnsupportedCryptoException, UnsupportedOperationException {

        String relative = withRelativePath ? Paths.get(this.domain, this.relativePath).toString() : this.getFileName();
        File destination = new File(destinationFolder.getAbsolutePath(), relative);
        if (destination.exists() && this.fileType != FileType.DIRECTORY)
            throw new FileAlreadyExistsException(destination.getAbsolutePath());

        Files.createDirectories(destination.getParentFile().toPath());

        this.extract(destination);

    }

    public void replaceWith(File newFile) throws IOException, BackupReadException, UnsupportedCryptoException, NotUnlockedException, DatabaseConnectionException {
        BasicFileAttributes newFileAttributes = Files.readAttributes(newFile.toPath(), BasicFileAttributes.class);
        if (!newFileAttributes.isRegularFile()) throw new IOException("Not a file");
        if (this.fileType != FileType.FILE) throw new UnsupportedOperationException("Not implemented yet");
        this.backupOriginal();
        this.size = newFileAttributes.size();
        this.properties.put("Size", this.size);
        if (this.isEncrypted()) {
            if (this.backup.manifest.getKeyBag().isEmpty())
                throw new BackupReadException("Encrypted file in non-encrypted backup");

            try {
                this.backup.manifest.getKeyBag().get().decryptFile(this.protectionClass, this.encryptionKey, newFile, this.contentFile);
            } catch (InvalidKeyException e) {
                throw new BackupReadException(e);
            }
        } else {
            Files.copy(newFile.toPath(), this.contentFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }

        this.backup.updateFileInfo(this.fileID, this.data.dict);
    }

    private void backupOriginal() throws IOException {
        File dir = new File(this.backup.directory, "_BackupExplorer");
        if (!dir.isDirectory() && !dir.mkdir())
            throw new IOException("Backup directory '" + dir.getAbsolutePath() + "' could not be created");

        // Incremental suffix
        String backupName = this.contentFile.getName();
        int i = 0;
        while (new File(dir, backupName + ".plist").exists() || new File(dir, backupName + ".bak").exists()) {
            backupName = this.contentFile.getName() + "." + (++i);
        }

        BinaryPropertyListWriter.write(new File(dir, backupName + ".plist"), this.data.dict);
        Files.copy(this.contentFile.toPath(), new File(dir, backupName + ".bak").toPath());
    }


    public enum FileType {
        FILE(1),
        DIRECTORY(2),
        SYMBOLIC_LINK(4);

        public final int flag;

        FileType(int flag) {
            this.flag = flag;
        }

        public static FileType fromFlags(int flags) throws BackupReadException {
            for (FileType type : values()) {
                if (type.flag == flags) return type;
            }

            throw new BackupReadException("Unknown file type " + flags);
        }
    }
}
