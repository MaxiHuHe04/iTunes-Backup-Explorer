package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.*;
import me.maxih.itunes_backup_explorer.util.BackupPathUtils;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

public class BackupFile {
    public final ITunesBackup backup;
    public final NSDictionary data;
    public final String fileID;
    public final String domain;
    public final String relativePath;
    public final int flags;

    private final FileType fileType;

    private final NSDictionary properties;
    private final NSArray objects;

    private File contentFile;

    private long size;
    private int protectionClass;
    private byte[] encryptionKey;

    public BackupFile(ITunesBackup backup, String fileID, String domain, String relativePath, int flags, NSDictionary data) throws BackupReadException {
        this.backup = backup;
        this.data = data;
        this.fileID = fileID;
        this.domain = domain;
        this.relativePath = relativePath;
        this.flags = flags;

        this.fileType = FileType.fromFlags(flags);

        try {
            this.objects = (NSArray) this.data.objectForKey("$objects");
            this.properties = (NSDictionary) getObject((UID) ((NSDictionary) data.objectForKey("$top")).objectForKey("root"));

            if (this.fileType == FileType.FILE) {
                this.contentFile = Paths.get(backup.directory.getAbsolutePath(), fileID.substring(0, 2), fileID).toFile();
                if (!this.contentFile.exists()) throw new BackupReadException("Missing file: " + this.fileID);

                this.size = ((NSNumber) this.properties.objectForKey("Size")).longValue();
                this.protectionClass = ((NSNumber) this.properties.objectForKey("ProtectionClass")).intValue();

                if (this.isEncrypted()) {
                    this.encryptionKey = new byte[40];
                    ByteBuffer encryptionKeyBuffer = ByteBuffer.wrap(this.encryptionKey);
                    ((NSData) ((NSDictionary) getObject((UID) properties.objectForKey("EncryptionKey"))).objectForKey("NS.data"))
                            .getBytes(encryptionKeyBuffer, 4, 40);
                }
            }
        } catch (ClassCastException | NullPointerException e) {
            throw new BackupReadException(e);
        }
    }

    private NSObject getObject(UID uid) {
        return this.objects.getArray()[uid.getBytes()[0]];
    }


    public FileType getFileType() {
        return fileType;
    }

    public Optional<File> getContentFile() {
        return Optional.ofNullable(contentFile);
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
                        byte[] key = this.backup.manifest.getKeyBag().get()
                                .unwrapKeyForClass(ByteBuffer.allocate(4).putInt(this.protectionClass).array(), this.encryptionKey);

                        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));

                        try (
                                CipherInputStream inputStream = new CipherInputStream(new FileInputStream(this.contentFile), c);
                                FileOutputStream fileOutputStream = new FileOutputStream(destination);
                                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream)
                        ) {
                            inputStream.transferTo(bufferedOutputStream);
                            bufferedOutputStream.flush();

                            fileOutputStream.getChannel().truncate(this.size);
                            long padding = this.size - fileOutputStream.getChannel().size();
                            if (padding != 0 && padding < Integer.MAX_VALUE) {
                                bufferedOutputStream.write(new byte[(int) padding]);
                            }
                        }

                    } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
                        throw new UnsupportedCryptoException(e);
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

    public void replaceWith(File newFile) {
        if (!newFile.exists() || newFile.isDirectory()) return;
        this.backupOriginal();
        // TODO
    }

    private void backupOriginal() {
        // TODO
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
