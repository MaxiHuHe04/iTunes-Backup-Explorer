package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSData;
import me.maxih.itunes_backup_explorer.util.BackupFilePaddingFixer;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class KeyBag {
    private static final Set<String> CLASS_KEY_TAGS = Set.of("CLAS", "WRAP", "WPKY", "KTYP", "PBKY");
    private static final int WRAP_DEVICE = 1;
    private static final int WRAP_PASSCODE = 2;

    private static final int BUFFER_SIZE = 16384;

    public int type;
    public byte[] uuid;
    public byte[] wrap;
    public final Map<ByteBuffer, Map<String, byte[]>> classKeys = new HashMap<>();
    public final Map<String, byte[]> attrs = new HashMap<>();

    private boolean unlocked = false;

    public KeyBag(NSData data) throws BackupReadException {
        this.parseBinaryBlob(data);
    }

    private void parseBinaryBlob(NSData data) throws BackupReadException {
        ByteBuffer buffer = ByteBuffer.wrap(data.bytes());

        try {
            Map<String, byte[]> currentClassKey = null;

            while (buffer.hasRemaining()) {
                byte[] tagBytes = new byte[4];
                buffer.get(tagBytes);
                String tag = new String(tagBytes);

                if (tag.equals("TYPE")) {
                    int length = buffer.getInt();
                    if (length != 4)
                        throw new BackupReadException("Expected integer in key bag but got " + length + " bytes");

                    this.type = buffer.getInt();
                    if (this.type > 3) throw new BackupReadException("Expected type <= 3 but got " + this.type);
                } else if (tag.equals("UUID") && this.uuid == null) {
                    int length = buffer.getInt();
                    if (length != 16)
                        throw new BackupReadException("Expected 16 byte uuid in key bag but got " + length + " bytes");

                    this.uuid = new byte[16];
                    buffer.get(this.uuid);
                } else if (tag.equals("WRAP") && this.wrap == null) {
                    int length = buffer.getInt();

                    this.wrap = new byte[length];
                    buffer.get(this.wrap);
                } else if (tag.equals("UUID")) {
                    int length = buffer.getInt();

                    byte[] value = new byte[length];
                    buffer.get(value);

                    if (currentClassKey != null)
                        this.classKeys.put(ByteBuffer.wrap(currentClassKey.get("CLAS")), currentClassKey);

                    currentClassKey = new HashMap<>();
                    currentClassKey.put("CLAS", value);
                } else if (CLASS_KEY_TAGS.contains(tag)) {
                    int length = buffer.getInt();

                    byte[] value = new byte[length];
                    buffer.get(value);

                    if (currentClassKey != null) currentClassKey.put(tag, value);
                } else {
                    int length = buffer.getInt();

                    byte[] value = new byte[length];
                    buffer.get(value);

                    this.attrs.put(tag, value);
                }

                if (currentClassKey != null) {
                    this.classKeys.put(ByteBuffer.wrap(currentClassKey.get("CLAS")), currentClassKey);
                }
            }
        } catch (BufferUnderflowException e) {
            throw new BackupReadException(e);
        }
    }

    public boolean isLocked() {
        return !this.unlocked;
    }

    public void unlock(String passcode) throws InvalidKeyException {
        try {
            byte[] salt1 = this.attrs.get("DPSL");
            int iterations1 = ByteBuffer.wrap(this.attrs.get("DPIC")).getInt();
            KeySpec spec1 = new PBEKeySpec(passcode.toCharArray(), salt1, iterations1, 32 * 8);

            SecretKeyFactory f1 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey key1 = f1.generateSecret(spec1);

            byte[] salt2 = this.attrs.get("SALT");
            int iterations2 = ByteBuffer.wrap(this.attrs.get("ITER")).getInt();
//            KeySpec spec2 = new PBEKeySpec(StandardCharsets.ISO_8859_1.decode(ByteBuffer.wrap(key1.getEncoded())).array(), salt2, iterations2, 32 * 8);

//            SecretKeyFactory f2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
//            SecretKey keyEncryptionKey = f2.generateSecret(spec2);

            // PBEKeySpec doesn't accept byte arrays and converting doesn't really work apparently
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA1Digest());
            gen.init(key1.getEncoded(), salt2, iterations2);
            byte[] keyEncryptionKey = ((KeyParameter) gen.generateDerivedParameters(32 * 8)).getKey();

            Cipher c = Cipher.getInstance("AESWrap");

            for (Map<String, byte[]> classKey : this.classKeys.values()) {
                if (!classKey.containsKey("WPKY")) continue;
                int wrap = ByteBuffer.wrap(classKey.get("WRAP")).getInt();
                if ((wrap & WRAP_PASSCODE) != 0) {
                    c.init(Cipher.UNWRAP_MODE, new SecretKeySpec(keyEncryptionKey, "AES"));
                    Key contentEncryptionKey = c.unwrap(classKey.get("WPKY"), "AES", Cipher.SECRET_KEY);
                    if (contentEncryptionKey != null) {
                        classKey.put("KEY", contentEncryptionKey.getEncoded());
                    }
                }
            }

            this.unlocked = true;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException e) {
            e.printStackTrace();
        }
    }

    public byte[] unwrapKeyForClass(byte[] protectionClass, byte[] persistentKey) throws BackupReadException, NotUnlockedException, InvalidKeyException, UnsupportedCryptoException {
        if (this.isLocked()) throw new NotUnlockedException();

        Map<String, byte[]> classKeyMap = this.classKeys.get(ByteBuffer.wrap(protectionClass));
        if (classKeyMap == null)
            throw new BackupReadException("Specified protection class '" + Arrays.toString(protectionClass) + "' was not found");

        byte[] classKey = classKeyMap.get("KEY");
        if (classKey == null)
            throw new BackupReadException("No class key was found for the specified protection class");

        if (persistentKey.length != 0x28)
            throw new BackupReadException("Invalid class key length");

        try {
            Cipher c = Cipher.getInstance("AESWrap");
            c.init(Cipher.UNWRAP_MODE, new SecretKeySpec(classKey, "AES"));
            return c.unwrap(persistentKey, "AES", Cipher.SECRET_KEY).getEncoded();
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new UnsupportedCryptoException(e);
        }
    }


    public InputStream decryptStream(byte[] protectionClass, byte[] persistentKey, InputStream source, String cipherMode) throws UnsupportedCryptoException, BackupReadException, NotUnlockedException, InvalidKeyException {
        byte[] key = this.unwrapKeyForClass(protectionClass, persistentKey);

        try {
            Cipher c = Cipher.getInstance(cipherMode);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return new CipherInputStream(source, c);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e) {
            throw new UnsupportedCryptoException(e);
        }
    }

    public InputStream decryptStream(byte[] protectionClass, byte[] persistentKey, InputStream source) throws UnsupportedCryptoException, BackupReadException, NotUnlockedException, InvalidKeyException {
        return decryptStream(protectionClass, persistentKey, source, "AES/CBC/PKCS5Padding");
    }

    protected void decryptFilePaddingFallback(byte[] protectionClass, byte[] persistentKey, File source, File destination, long size) throws IOException, BackupReadException, UnsupportedCryptoException, NotUnlockedException, InvalidKeyException {
        try (
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
                InputStream decryptStream = decryptStream(protectionClass, persistentKey, inputStream, "AES/CBC/NoPadding");

                FileOutputStream fileOutputStream = new FileOutputStream(destination);
                BufferedOutputStream outputStream = new BufferedOutputStream(fileOutputStream, BUFFER_SIZE)
        ) {
            decryptStream.transferTo(outputStream);
            outputStream.flush();

            if (size != -1L && fileOutputStream.getChannel().size() != size) {
                System.out.printf("Warning: File size from database doesn't match actual decrypted size - expected %9d, got %9d (%s)%n", size, fileOutputStream.getChannel().size(), destination.getPath());
            }

            BackupFilePaddingFixer.tryFixPadding(destination);
        }
    }

    public void decryptFile(byte[] protectionClass, byte[] persistentKey, File source, File destination, long size) throws IOException, BackupReadException, UnsupportedCryptoException, NotUnlockedException, InvalidKeyException {
        try (
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
                InputStream decryptStream = decryptStream(protectionClass, persistentKey, inputStream);

                FileOutputStream fileOutputStream = new FileOutputStream(destination);
                BufferedOutputStream outputStream = new BufferedOutputStream(fileOutputStream, BUFFER_SIZE)
        ) {
            decryptStream.transferTo(outputStream);
            outputStream.flush();

            if (size != -1L && fileOutputStream.getChannel().size() != size) {
                System.out.printf("Warning: File size from database doesn't match actual decrypted size - expected %9d, got %9d (%s)%n", size, fileOutputStream.getChannel().size(), destination.getPath());
            }
        } catch (IOException e) {
            if (e.getCause() instanceof BadPaddingException) {
                System.out.println("Warning: Bad padding - " + e.getMessage() + " (" + destination.getPath() + ")");
                System.out.println("Trying to decrypt again without padding...");
                decryptFilePaddingFallback(protectionClass, persistentKey, source, destination, size);
            } else {
                throw e;
            }
        }
    }

    public void decryptFile(int protectionClass, byte[] persistentKey, File source, File destination, long size) throws BackupReadException, UnsupportedCryptoException, NotUnlockedException, IOException, InvalidKeyException {
        decryptFile(ByteBuffer.allocate(4).putInt(protectionClass).array(), persistentKey, source, destination, size);
    }

    public void decryptFile(int protectionClass, byte[] persistentKey, File source, File destination) throws BackupReadException, UnsupportedCryptoException, NotUnlockedException, IOException, InvalidKeyException {
        decryptFile(protectionClass, persistentKey, source, destination, -1);
    }

    public OutputStream encryptStream(byte[] protectionClass, byte[] persistentKey, OutputStream destination) throws BackupReadException, UnsupportedCryptoException, NotUnlockedException, InvalidKeyException {
        byte[] key = this.unwrapKeyForClass(protectionClass, persistentKey);

        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return new CipherOutputStream(destination, c);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e) {
            throw new UnsupportedCryptoException(e);
        }
    }

    public void encryptFile(byte[] protectionClass, byte[] persistentKey, File source, File destination) throws BackupReadException, UnsupportedCryptoException, NotUnlockedException, InvalidKeyException, IOException {
        try (
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);

                BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destination), BUFFER_SIZE);
                OutputStream encryptStream = encryptStream(protectionClass, persistentKey, outputStream)
        ) {
            inputStream.transferTo(encryptStream);
        }
    }

    public void encryptFile(int protectionClass, byte[] persistentKey, File source, File destination) throws BackupReadException, UnsupportedCryptoException, NotUnlockedException, IOException, InvalidKeyException {
        encryptFile(ByteBuffer.allocate(4).putInt(protectionClass).array(), persistentKey, source, destination);
    }

}