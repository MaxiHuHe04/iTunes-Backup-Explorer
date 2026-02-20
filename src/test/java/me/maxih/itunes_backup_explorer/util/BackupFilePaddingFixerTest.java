package me.maxih.itunes_backup_explorer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BackupFilePaddingFixerTest {

    @TempDir
    Path tempDir;

    private File createFileWithContent(String name, byte[] content) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content);
        }
        return file;
    }

    @Test
    void tryFixPadding_validPKCS7Padding() throws IOException {
        byte[] data = new byte[1024];
        Arrays.fill(data, 0, 1018, (byte) 0xAA);
        Arrays.fill(data, 1018, 1024, (byte) 6);

        File file = createFileWithContent("valid-padding.bin", data);
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(1018, file.length());
    }

    @Test
    void tryFixPadding_withTrailingZeros() throws IOException {
        byte[] data = new byte[2048];
        Arrays.fill(data, 0, 1018, (byte) 0xAA);
        Arrays.fill(data, 1018, 1024, (byte) 6);

        File file = createFileWithContent("trailing-zeros.bin", data);
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(1018, file.length());
    }

    @Test
    void tryFixPadding_invalidPaddingBytes_noChange() throws IOException {
        byte[] data = new byte[1024];
        Arrays.fill(data, 0, 1018, (byte) 0xAA);
        data[1018] = 6;
        data[1019] = 6;
        data[1020] = 6;
        data[1021] = 5;
        data[1022] = 6;
        data[1023] = 6;

        File file = createFileWithContent("invalid-padding.bin", data);
        long originalSize = file.length();
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(originalSize, file.length());
    }

    @Test
    void tryFixPadding_emptyFile_noChange() throws IOException {
        File file = createFileWithContent("empty.bin", new byte[0]);
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(0, file.length());
    }

    @Test
    void tryFixPadding_allZeros_noChange() throws IOException {
        byte[] data = new byte[1024];
        File file = createFileWithContent("all-zeros.bin", data);
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(1024, file.length());
    }

    @Test
    void tryFixPadding_sizeNotMultipleOf16_noChange() throws IOException {
        byte[] data = new byte[1024];
        Arrays.fill(data, 0, 1020, (byte) 0xAA);

        File file = createFileWithContent("non-multiple-16.bin", data);
        long originalSize = file.length();
        BackupFilePaddingFixer.tryFixPadding(file);

        assertEquals(originalSize, file.length());
    }
}
