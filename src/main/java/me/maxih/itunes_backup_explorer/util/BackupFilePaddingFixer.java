package me.maxih.itunes_backup_explorer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class BackupFilePaddingFixer {
    private static final Logger logger = LoggerFactory.getLogger(BackupFilePaddingFixer.class);
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
            long fileLength = raf.length();
            if (fileLength < 16) return;

            long position = fileLength;

            outerLoop:
            while (position > 0) {
                int readSize = (int) Math.min(BUFFER_SIZE, position);
                position -= readSize;
                raf.seek(position);

                byte[] buffer = new byte[readSize];
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

            logger.debug("Assuming padding of {} bytes", paddingNumber);

            if (actualSize < paddingNumber) {
                logger.debug("File too small");
                return;
            }

            if (actualSize % 16 != 0) {
                logger.debug("Actual size is not a multiple of 16. File is not correctly padded");
                return;
            }

            raf.seek(actualSize - paddingNumber);

            byte[] paddingBytes = new byte[paddingNumber];
            raf.readFully(paddingBytes);

            for (int i = 0; i < paddingNumber; i++) {
                if (paddingBytes[i] != paddingNumber) {
                    logger.debug("Invalid padding byte #{}: {} != {}", i, paddingBytes[i], paddingNumber);
                    return;
                }
            }

            actualSize -= paddingNumber;

            raf.setLength(actualSize);
        }
    }

}
