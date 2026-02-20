package me.maxih.itunes_backup_explorer.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileSizeTest {

    @Test
    void format_zero() {
        assertEquals("0 B", FileSize.format(0));
    }

    @Test
    void format_negative() {
        assertEquals("0 B", FileSize.format(-100));
    }

    @Test
    void format_bytes() {
        assertEquals("512 B", FileSize.format(512));
    }

    @Test
    void format_oneByteBelow1KB() {
        assertEquals("1023 B", FileSize.format(1023));
    }

    @Test
    void format_exactlyOneKB() {
        assertEquals("1.0 KB", FileSize.format(1024));
    }

    @Test
    void format_kilobytes() {
        assertEquals("1.5 KB", FileSize.format(1536));
    }

    @Test
    void format_megabytes() {
        assertEquals("10.0 MB", FileSize.format(10L * 1024 * 1024));
    }

    @Test
    void format_gigabytes() {
        assertEquals("2.5 GB", FileSize.format((long) (2.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void format_terabytes() {
        assertEquals("1.0 TB", FileSize.format(1024L * 1024 * 1024 * 1024));
    }
}
