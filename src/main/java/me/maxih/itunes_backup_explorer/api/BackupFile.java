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
import java.util.Optional;

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
                if (!this.contentFile.exists())
                    throw new BackupReadException("Missing file: " + this.fileID + " in " + domain + " (" + relativePath + ")");

                this.size = this.properties.get(NSNumber.class, "Size").orElseThrow().longValue();
                this.protectionClass = this.properties.get(NSNumber.class, "ProtectionClass").orElseThrow().intValue();

                Optional<UID> encryptionKeyUID = this.properties.get(UID.class, "EncryptionKey");
                if (encryptionKeyUID.isPresent()) {
                    this.encryptionKey = new byte[40];
                    ByteBuffer encryptionKeyBuffer = ByteBuffer.wrap(this.encryptionKey);
                    new UtilDict(this.getObject(NSDictionary.class, encryptionKeyUID.get()))
                            .getData("NS.data")
                            .orElseThrow()
                            .getBytes(encryptionKeyBuffer, 4, 40);
                } else {
                    this.encryptionKey = null;
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
        return this.encryptionKey != null;
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
                        this.backup.manifest.getKeyBag().get().decryptFile(this.protectionClass, this.encryptionKey, this.contentFile, destination, size);
                    } catch (InvalidKeyException e) {
                        throw new BackupReadException(e);
                    }

                    //noinspection ResultOfMethodCallIgnored
                    this.properties.get(NSNumber.class, "LastModified")
                            .map(NSNumber::longValue)
                            .map(seconds -> seconds * 1000)
                            .ifPresent(destination::setLastModified);
                } else {
                    Files.copy(this.contentFile.toPath(), destination.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                break;
            case SYMBOLIC_LINK:
                throw new UnsupportedOperationException("Not implemented yet");
        }

    }

    public void extractToFolder(File destinationFolder, boolean withRelativePath)
            throws IOException, BackupReadException, NotUnlockedException, UnsupportedCryptoException, UnsupportedOperationException {

        String relative;

        try {
            relative = withRelativePath ? Paths.get(this.domain, this.relativePath).toString() : this.getFileName();
        } catch (InvalidPathException e) {
            try {
                relative = withRelativePath
                        ? Paths.get(this.domain, BackupPathUtils.cleanPath(this.relativePath)).toString()
                        : BackupPathUtils.cleanPath(this.getFileName());
                System.out.println("Continuing with invalid characters replaced: " + this.getFileName() + " -> " + relative);
            } catch (InvalidPathException e1) {
                throw new IOException("Invalid character in filename, failed to replace", e1);
            }
        }
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
                this.backup.manifest.getKeyBag().get().encryptFile(this.protectionClass, this.encryptionKey, newFile, this.contentFile);
            } catch (InvalidKeyException e) {
                throw new BackupReadException(e);
            }
        } else {
            Files.copy(newFile.toPath(), this.contentFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }

        this.backup.updateFileInfo(this.fileID, this.data.dict);
    }

    public void delete() throws IOException, DatabaseConnectionException {
        if (this.fileType != FileType.FILE) throw new UnsupportedOperationException("Not implemented yet");
        this.backupOriginal();
        Files.delete(this.contentFile.toPath());
        this.backup.removeFileFromDatabase(this.fileID);
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
