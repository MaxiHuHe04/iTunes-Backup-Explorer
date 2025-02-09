package me.maxih.itunes_backup_explorer.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class BackupFilePaddingFixer {
    private static final int BUFFER_SIZE = 1024;

    /**
     * Removes padding from files that were originally encrypted using PKCS#7,
     * but then decrypted without proper padding handling, possibly padded with a
     * wrong size from the database, and re-encrypted with NoPadding as by
     * older versions of this program.
     * If no PKCS-like padding is detected at the end before trailing zeros,
     * the file is not changed.
     * @param file the file to remove padding from
     * @throws IOException file not found or I/O error
     */
    public static void tryFixPadding(File file) throws IOException {
        long actualSize = 0;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long position = raf.length();

            outerLoop:
            while (position > 0) {
                position -= BUFFER_SIZE;
                raf.seek(Math.max(0L, position));

                byte[] buffer = new byte[BUFFER_SIZE];
                raf.readFully(buffer);

                for (int i = buffer.length - 1; i > 0; i--) {
                    if (buffer[i] != 0x00) {
                        actualSize = position + i + 1;
                        break outerLoop;
                    }
                }
            }

            if (actualSize == 0) return;

            raf.seek(actualSize - 1);
            int paddingNumber = raf.read();

            System.out.println("Assuming padding of " + paddingNumber + " bytes.");

            if (actualSize < paddingNumber) {
                System.out.println("File is too small.");
                return;
            }

            if (actualSize % 16 != 0) {
                System.out.println("Actual size is not a multiple of 16. File is not padded correctly.");
                return;
            }

            raf.seek(actualSize - paddingNumber);

            byte[] paddingBytes = new byte[paddingNumber];
            raf.readFully(paddingBytes);

            for (int i = 0; i < paddingNumber; i++) {
                if (paddingBytes[i] != paddingNumber) {
                    System.out.println("Padding byte #" + i + " invalid: " + paddingBytes[i] + " != " + paddingNumber);
                    return;
                }
            }

            actualSize -= paddingNumber;

            raf.setLength(actualSize);
        }
    }

}
