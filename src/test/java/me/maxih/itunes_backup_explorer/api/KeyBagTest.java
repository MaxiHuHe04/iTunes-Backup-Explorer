package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.NSData;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KeyBagTest {

    private static byte[] protectionClass;
    private static byte[] persistentKey;
    private static KeyBag keyBag;

    @BeforeAll
    static void create() throws Exception {
        var data = ByteBuffer.allocate(1024);

        var uuid = UUID.randomUUID();
        data.put("UUID".getBytes(StandardCharsets.US_ASCII));
        data.putInt(16);
        data.putLong(uuid.getMostSignificantBits());
        data.putLong(uuid.getLeastSignificantBits());

        data.put("WRAP".getBytes(StandardCharsets.US_ASCII));
        data.putInt(4);
        data.putInt(2);

        // Salt 1
        var salt1 = new byte[32];
        ThreadLocalRandom.current().nextBytes(salt1);
        data.put("DPSL".getBytes(StandardCharsets.US_ASCII));
        data.putInt(salt1.length);
        data.put(salt1);

        // Iterations 1
        data.put("DPIC".getBytes(StandardCharsets.US_ASCII));
        data.putInt(4);
        data.putInt(1);

        // Salt 2
        var salt2 = new byte[32];
        ThreadLocalRandom.current().nextBytes(salt2);
        data.put("SALT".getBytes(StandardCharsets.US_ASCII));
        data.putInt(salt2.length);
        data.put(salt2);

        // Iterations 2
        data.put("ITER".getBytes(StandardCharsets.US_ASCII));
        data.putInt(4);
        data.putInt(1);

        var uuid2 = UUID.randomUUID();
        protectionClass = new byte[16];
        ByteBuffer.wrap(protectionClass)
                .putLong(uuid2.getMostSignificantBits())
                .putLong(uuid2.getLeastSignificantBits());

        data.put("UUID".getBytes(StandardCharsets.US_ASCII));
        data.putInt(protectionClass.length);
        data.put(protectionClass);

        KeySpec spec1 = new PBEKeySpec("password".toCharArray(), salt1, 1, 32 * 8);
        SecretKeyFactory f1 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKey key1 = f1.generateSecret(spec1);
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA1Digest());
        gen.init(key1.getEncoded(), salt2, 1);
        byte[] keyEncryptionKey = ((KeyParameter) gen.generateDerivedParameters(32 * 8)).getKey();

        Cipher c = Cipher.getInstance("AESWrap");
        c.init(Cipher.WRAP_MODE, new SecretKeySpec(keyEncryptionKey, "AES"));
        persistentKey = c.wrap(key1);

        // Why is keyEncryptionKey getting encrypted with keyEncryptionKey?
        c.init(Cipher.WRAP_MODE, new SecretKeySpec(keyEncryptionKey, "AES"));
        var wrappedKey = c.wrap(new SecretKeySpec(keyEncryptionKey, "AES"));
        data.put("WPKY".getBytes(StandardCharsets.US_ASCII));
        data.putInt(wrappedKey.length);
        data.put(wrappedKey);

        data.put("WRAP".getBytes(StandardCharsets.US_ASCII));
        data.putInt(4);
        data.putInt(2);

        data.flip();
        var d2 = new byte[data.remaining()];
        data.get(d2);

        keyBag = new KeyBag(new NSData(d2));
        keyBag.unlock("password");
    }

    @Test
    void decryptFile() throws Exception {
        for (int size = 0; size < 128; ++size) {
            var plain = Files.createTempFile("plain-" + size, "-");
            plain.toFile().deleteOnExit();
            var cipher = Files.createTempFile("cipher-" + size, "-");
            cipher.toFile().deleteOnExit();
            var verify = Files.createTempFile("verify-" + size, "-");
            verify.toFile().deleteOnExit();

            var data = new byte[size];
            ThreadLocalRandom.current().nextBytes(data);
            Files.write(plain, data);

            keyBag.encryptFile(protectionClass, persistentKey, plain.toFile(), cipher.toFile());
            if (size > 0) {
                assertFalse(Arrays.equals(Files.readAllBytes(plain), Files.readAllBytes(cipher)));
            }

            keyBag.decryptFile(protectionClass, persistentKey, cipher.toFile(), verify.toFile(), size);
            assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(verify));
        }
    }
}

