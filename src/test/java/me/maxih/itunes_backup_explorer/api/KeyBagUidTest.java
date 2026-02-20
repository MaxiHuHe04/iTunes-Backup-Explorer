package me.maxih.itunes_backup_explorer.api;

import com.dd.plist.UID;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class KeyBagUidTest {

    @Test
    void uidToIndex_singleByte_zero() {
        UID uid = new UID("test", BigInteger.valueOf(0));
        assertEquals(0, BackupFile.uidToIndex(uid));
    }

    @Test
    void uidToIndex_singleByte_positive() {
        UID uid = new UID("test", BigInteger.valueOf(127));
        assertEquals(127, BackupFile.uidToIndex(uid));
    }

    @Test
    void uidToIndex_singleByte_overflowBoundary() {
        UID uid = new UID("test", BigInteger.valueOf(128));
        assertEquals(128, BackupFile.uidToIndex(uid));
    }

    @Test
    void uidToIndex_singleByte_maxUnsigned() {
        UID uid = new UID("test", BigInteger.valueOf(255));
        assertEquals(255, BackupFile.uidToIndex(uid));
    }

    @Test
    void uidToIndex_multiByte() {
        UID uid = new UID("test", BigInteger.valueOf(256));
        assertEquals(256, BackupFile.uidToIndex(uid));
    }

    @Test
    void uidToIndex_largeMultiByte() {
        UID uid = new UID("test", BigInteger.valueOf(1000));
        assertEquals(1000, BackupFile.uidToIndex(uid));
    }
}
