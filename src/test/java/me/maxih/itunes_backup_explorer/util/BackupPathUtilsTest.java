package me.maxih.itunes_backup_explorer.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupPathUtilsTest {

    @Test
    void getParentPath_withNestedPath() {
        assertEquals("Library/SMS", BackupPathUtils.getParentPath("Library/SMS/sms.db"));
    }

    @Test
    void getParentPath_withSingleSegment() {
        assertEquals("", BackupPathUtils.getParentPath("file.txt"));
    }

    @Test
    void getParentPath_withEmptyString() {
        assertEquals("", BackupPathUtils.getParentPath(""));
    }

    @Test
    void getFileName_withNestedPath() {
        assertEquals("sms.db", BackupPathUtils.getFileName("Library/SMS/sms.db"));
    }

    @Test
    void getFileName_withSingleSegment() {
        assertEquals("file.txt", BackupPathUtils.getFileName("file.txt"));
    }

    @Test
    void getFileName_withEmptyString() {
        assertEquals("", BackupPathUtils.getFileName(""));
    }

    @Test
    void getPathLevel_withEmptyString() {
        assertEquals(0, BackupPathUtils.getPathLevel(""));
    }

    @Test
    void getPathLevel_withSingleSegment() {
        assertEquals(1, BackupPathUtils.getPathLevel("file.txt"));
    }

    @Test
    void getPathLevel_withMultipleSegments() {
        assertEquals(3, BackupPathUtils.getPathLevel("Library/SMS/sms.db"));
    }

    @Test
    void getFileExtension_withExtension() {
        assertEquals("db", BackupPathUtils.getFileExtension("Library/SMS/sms.db"));
    }

    @Test
    void getFileExtension_withoutExtension() {
        assertEquals("", BackupPathUtils.getFileExtension("Library/SMS/sms"));
    }

    @Test
    void getFileExtension_withMultipleDots() {
        assertEquals("gz", BackupPathUtils.getFileExtension("archive.tar.gz"));
    }

    @Test
    void cleanPath_replacesInvalidCharacters() {
        assertEquals("file-name-.txt", BackupPathUtils.cleanPath("file:name*.txt"));
    }

    @Test
    void cleanPath_preservesValidCharacters() {
        assertEquals("Library/SMS/sms.db", BackupPathUtils.cleanPath("Library/SMS/sms.db"));
    }

    @Test
    void cleanPath_replacesAllInvalidTypes() {
        assertEquals("a-b-c-d-e-f-g", BackupPathUtils.cleanPath("a:b*c?d\"e<f>g"));
    }
}
