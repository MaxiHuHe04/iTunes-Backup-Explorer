package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import org.xml.sax.SAXException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.text.ParseException;
import java.util.Date;
import java.util.*;
import java.util.stream.Collectors;

public class ITunesBackup {
    public static List<ITunesBackup> getBackups(File backupRoot) {
        if (!backupRoot.isDirectory()) return new ArrayList<>();

        File[] backupDirectories = backupRoot.listFiles(File::isDirectory);

        return Arrays
                .stream(Objects.requireNonNullElse(backupDirectories, new File[0]))
                .map(dir -> {
                    try {
                        return new ITunesBackup(dir);
                    } catch (FileNotFoundException e) {
                        return null;
                    } catch (BackupReadException e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.<ITunesBackup, Date>comparing(backup -> backup.manifest.date).reversed())
                .collect(Collectors.toList());
    }


    public File directory;
    public File manifestDBFile;
    public File manifestPListFile;
    public NSDictionary manifestPList;
    public BackupManifest manifest;

    public File decryptedDatabaseFile;
    private Connection databaseCon;

    public ITunesBackup(File directory) throws FileNotFoundException, BackupReadException {
        if (!directory.exists()) throw new FileNotFoundException(directory.getAbsolutePath());

        this.directory = directory;
        this.manifestDBFile = new File(directory, "Manifest.db");
        this.manifestPListFile = new File(directory, "Manifest.plist");

        if (!manifestDBFile.exists()) throw new FileNotFoundException(manifestDBFile.getAbsolutePath());
        if (!manifestPListFile.exists()) throw new FileNotFoundException(manifestPListFile.getAbsolutePath());

        this.loadManifest();

        if (this.manifest.encrypted && this.manifest.getKeyBag().isEmpty())
            throw new BackupReadException("Backup is encrypted but no key bag was found");

        if (!this.manifest.encrypted) this.decryptedDatabaseFile = this.manifestDBFile;
    }

    private void loadManifest() throws BackupReadException {
        try {
            this.manifestPList = (NSDictionary) PropertyListParser.parse(manifestPListFile);
            this.manifest = new BackupManifest(manifestPList);
        } catch (Exception e) {
            throw new BackupReadException(e.getMessage(), e);
        }
    }

    public boolean isLocked() {
        return this.manifest.encrypted
                && this.manifest.getKeyBag().isPresent()
                && this.manifest.getKeyBag().get().isLocked();
    }

    public void decryptDatabase() throws BackupReadException {
        if (!this.manifest.encrypted || this.manifest.getKeyBag().isEmpty()) return;

        byte[] manifestClass = new byte[4];
        byte[] manifestKey = new byte[this.manifest.manifestKey.length() - 4];
        ByteBuffer manifestKeyBuffer = ByteBuffer.wrap(this.manifest.manifestKey.bytes());

        ByteBuffer.wrap(manifestClass).putInt(manifestKeyBuffer.order(ByteOrder.LITTLE_ENDIAN).getInt());

        manifestKeyBuffer.order(ByteOrder.BIG_ENDIAN).get(manifestKey);

        try {
            byte[] key = this.manifest.getKeyBag().get().unwrapKeyForClass(manifestClass, manifestKey);

            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            CipherInputStream inputStream = new CipherInputStream(new FileInputStream(manifestDBFile), c);

            this.decryptedDatabaseFile = File.createTempFile("decrypted-manifest", ".sqlite3");
            BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(decryptedDatabaseFile));
            inputStream.transferTo(outputStream);
            inputStream.close();
            outputStream.close();
        } catch (FileNotFoundException | InvalidKeyException e) {
            throw new BackupReadException(e);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | IOException
                | InvalidAlgorithmParameterException | NotUnlockedException | UnsupportedCryptoException e) {
            e.printStackTrace();
        }
    }

    public boolean connectToDatabase() {
        if (decryptedDatabaseFile == null) return false;

        try {
            databaseCon = DriverManager.getConnection("jdbc:sqlite:" + decryptedDatabaseFile.getCanonicalPath());
            System.out.println("Connection to the backup database of '" + this.manifest.deviceName + "' has been established.");
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    public boolean databaseConnected() {
        try {
            return this.databaseCon != null && !this.databaseCon.isClosed();
        } catch (SQLException e) {
            e.printStackTrace();
            this.databaseCon = null;
            return false;
        }
    }

    public void cleanUp() {
        if (!this.manifest.encrypted
                || this.decryptedDatabaseFile == null
                || !this.decryptedDatabaseFile.exists()
                || this.decryptedDatabaseFile == this.manifestDBFile) return;

        try {
            if (databaseCon != null && !databaseCon.isClosed())
                this.databaseCon.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if (!this.decryptedDatabaseFile.delete())
            System.out.println("Could not delete temporary file " + this.decryptedDatabaseFile.getAbsolutePath());
    }

    private List<BackupFile> queryFiles(String sql, StatementPreparation preparation) {
        if (!databaseConnected() && !this.connectToDatabase()) return new ArrayList<>(0);

        try {
            PreparedStatement statement = this.databaseCon.prepareStatement(sql);
            preparation.prepare(statement);

            ResultSet result = statement.executeQuery();

            List<BackupFile> backupFiles = new ArrayList<>();
            while (result.next()) {
                try {
                    backupFiles.add(new BackupFile(
                            this,
                            result.getString(1),
                            result.getString(2),
                            result.getString(3),
                            result.getInt(4),
                            (NSDictionary) PropertyListParser.parse(result.getBinaryStream(5))
                    ));
                } catch (IOException | PropertyListFormatException | ParseException | ParserConfigurationException | SAXException | BackupReadException e) {
                    e.printStackTrace();
                }
            }

            return backupFiles;
        } catch (SQLException e) {
            e.printStackTrace();
            return new ArrayList<>(0);
        }
    }

    public List<BackupFile> searchFiles(String domainLike, String relativePathLike) {
        return this.queryFiles(
                "SELECT * FROM files WHERE `domain` LIKE ? AND `relativePath` LIKE ? ESCAPE '\\' ORDER BY `flags`, `domain`, `relativePath`",
                statement -> {
                    statement.setString(1, domainLike);
                    statement.setString(2, relativePathLike);
                }
        );
    }

    public List<BackupFile> queryDomainRoots() {
        return queryFiles("SELECT * FROM files WHERE `relativePath` = \"\" ORDER BY `domain`", statement -> {});
    }

    public List<BackupFile> queryDomainFiles(boolean withDomainRoot, String... domains) {
        if (domains.length == 0) return new ArrayList<>(0);
        return queryFiles(
                "SELECT * FROM files " +
                        "WHERE `domain` IN (?" + ", ?".repeat(domains.length - 1) + ") " +
                        (withDomainRoot ? " " : "AND `relativePath` <> \"\" ") +
                        "ORDER BY `flags`, `domain`, `relativePath`",
                statement -> {
                    for (int i = 0; i < domains.length; i++) statement.setString(i + 1, domains[i]);
                }
        );
    }


    @FunctionalInterface
    private interface StatementPreparation {
        void prepare(PreparedStatement statement) throws SQLException;
    }

}
