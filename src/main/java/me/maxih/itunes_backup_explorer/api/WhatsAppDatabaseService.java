package me.maxih.itunes_backup_explorer.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class WhatsAppDatabaseService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(WhatsAppDatabaseService.class);
    private static final long CORE_DATA_EPOCH_OFFSET = 978307200L;

    private final Connection connection;
    private final Set<String> messageColumns;
    private final Set<String> sessionColumns;
    private final boolean hasMediaTable;
    private final Set<String> mediaColumns;
    private String whatsappDomain;

    public WhatsAppDatabaseService(File databaseFile) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        this.connection.setAutoCommit(false);
        this.messageColumns = getTableColumns("ZWAMESSAGE");
        this.sessionColumns = getTableColumns("ZWACHATSESSION");
        this.hasMediaTable = tableExists("ZWAMEDIAITEM");
        this.mediaColumns = hasMediaTable ? getTableColumns("ZWAMEDIAITEM") : Collections.emptySet();
        logger.info("WhatsApp DB schema detected — ZWAMESSAGE cols: {}, ZWACHATSESSION cols: {}, ZWAMEDIAITEM(exists={}): {}",
                messageColumns, sessionColumns, hasMediaTable, mediaColumns);
    }

    private Set<String> getTableColumns(String table) {
        Set<String> columns = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toUpperCase(Locale.ROOT));
            }
        } catch (SQLException e) {
            logger.warn("Failed to read table info for {}", table, e);
        }
        return columns;
    }

    private boolean tableExists(String table) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    public List<WhatsAppChat> queryChats() throws SQLException {
        List<WhatsAppChat> chats = new ArrayList<>();

        boolean hasPushName = messageColumns.contains("ZPUSHNAME");
        boolean hasChatSession = messageColumns.contains("ZCHATSESSION");
        boolean hasMessageCounter = sessionColumns.contains("ZMESSAGECOUNTER");

        logDatabaseStats(hasChatSession);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT cs.Z_PK, cs.ZCONTACTJID, cs.ZPARTNERNAME, cs.ZSESSIONTYPE, ");
        sql.append("cs.ZLASTMESSAGEDATE");

        if (hasChatSession) {
            sql.append(", (SELECT COUNT(*) FROM ZWAMESSAGE m WHERE m.ZCHATSESSION = cs.Z_PK) AS totalMessageCount");
        } else if (hasMessageCounter) {
            sql.append(", COALESCE(cs.ZMESSAGECOUNTER, 0) AS totalMessageCount");
        } else {
            sql.append(", 0 AS totalMessageCount");
        }

        if (hasChatSession) {
            sql.append(", (SELECT m.ZTEXT FROM ZWAMESSAGE m WHERE m.ZCHATSESSION = cs.Z_PK ");
            sql.append("AND m.ZTEXT IS NOT NULL AND m.ZTEXT != '' ");
            sql.append("ORDER BY m.ZMESSAGEDATE DESC LIMIT 1) AS ZLASTMESSAGETEXT");
        }

        if (hasPushName && hasChatSession) {
            sql.append(", (SELECT m2.ZPUSHNAME FROM ZWAMESSAGE m2 WHERE m2.ZCHATSESSION = cs.Z_PK ");
            sql.append("AND m2.ZISFROMME = 0 AND m2.ZPUSHNAME IS NOT NULL AND m2.ZPUSHNAME != '' ");
            sql.append("ORDER BY m2.ZMESSAGEDATE DESC LIMIT 1) AS latestPushName");
        }

        sql.append(" FROM ZWACHATSESSION cs ");
        sql.append("WHERE ((cs.ZCONTACTJID IS NOT NULL AND cs.ZCONTACTJID != '') OR cs.ZSESSIONTYPE = 1) ");
        if (hasChatSession) {
            sql.append("AND EXISTS (SELECT 1 FROM ZWAMESSAGE m WHERE m.ZCHATSESSION = cs.Z_PK) ");
        } else if (hasMessageCounter) {
            sql.append("AND COALESCE(cs.ZMESSAGECOUNTER, 0) > 0 ");
        }
        sql.append("ORDER BY cs.ZLASTMESSAGEDATE DESC");

        logger.debug("Chats query: {}", sql);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql.toString())) {
            while (rs.next()) {
                int msgCount = rs.getInt("totalMessageCount");
                String lastMsg = hasChatSession ? rs.getString("ZLASTMESSAGETEXT") : null;

                chats.add(new WhatsAppChat(
                        rs.getLong("Z_PK"),
                        rs.getString("ZCONTACTJID"),
                        rs.getString("ZPARTNERNAME"),
                        rs.getInt("ZSESSIONTYPE"),
                        msgCount,
                        toInstant(rs.getDouble("ZLASTMESSAGEDATE")),
                        lastMsg,
                        (hasPushName && hasChatSession) ? rs.getString("latestPushName") : null
                ));
            }
        }

        logger.info("Loaded {} WhatsApp chats", chats.size());
        return chats;
    }

    public List<WhatsAppMessage> queryMessages(long chatSessionId, int limit, int offset) throws SQLException {
        List<WhatsAppMessage> messages = new ArrayList<>();

        boolean hasStarred = messageColumns.contains("ZSTARRED");
        boolean hasPushName = messageColumns.contains("ZPUSHNAME");
        boolean hasMediaLocalPath = mediaColumns.contains("ZMEDIALOCALPATH");
        boolean hasThumbnailData = mediaColumns.contains("ZTHUMBNAILDATA");
        boolean hasThumbnailLocalPath = mediaColumns.contains("ZTHUMBNAILLOCALPATH");
        boolean hasXmppThumbPath = mediaColumns.contains("ZXMPPTHUMBPATH");
        boolean hasVcardString = mediaColumns.contains("ZVCARDSTRING");
        boolean hasFileSize = mediaColumns.contains("ZFILESIZE");
        boolean hasTitle = mediaColumns.contains("ZTITLE");
        boolean hasMediaMessage = mediaColumns.contains("ZMESSAGE");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.Z_PK, m.ZCHATSESSION, m.ZISFROMME, m.ZMESSAGETYPE, m.ZMESSAGEDATE, ");
        sql.append("m.ZFROMJID, m.ZTEXT");
        if (hasStarred) sql.append(", m.ZSTARRED");
        if (hasPushName) sql.append(", m.ZPUSHNAME");
        if (hasMediaTable && hasMediaMessage) {
            if (hasMediaLocalPath) sql.append(", mi.ZMEDIALOCALPATH");
            if (hasThumbnailData) sql.append(", mi.ZTHUMBNAILDATA");
            if (hasThumbnailLocalPath) sql.append(", mi.ZTHUMBNAILLOCALPATH");
            if (hasXmppThumbPath) sql.append(", mi.ZXMPPTHUMBPATH");
            if (hasVcardString) sql.append(", mi.ZVCARDSTRING");
            if (hasFileSize) sql.append(", mi.ZFILESIZE");
            if (hasTitle) sql.append(", mi.ZTITLE");
        }
        sql.append(" FROM ZWAMESSAGE m");
        if (hasMediaTable && hasMediaMessage) {
            sql.append(" LEFT JOIN ZWAMEDIAITEM mi ON mi.ZMESSAGE = m.Z_PK");
        }
        sql.append(" WHERE m.ZCHATSESSION = ? ORDER BY m.ZMESSAGEDATE DESC LIMIT ? OFFSET ?");

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            stmt.setLong(1, chatSessionId);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    WhatsAppMedia media = null;
                    if (hasMediaTable && hasMediaMessage) {
                        String mediaPath = hasMediaLocalPath ? rs.getString("ZMEDIALOCALPATH") : null;
                        byte[] thumbnailData = hasThumbnailData ? rs.getBytes("ZTHUMBNAILDATA") : null;
                        String thumbnailLocalPath = hasThumbnailLocalPath ? rs.getString("ZTHUMBNAILLOCALPATH") : null;
                        String xmppThumbPath = hasXmppThumbPath ? rs.getString("ZXMPPTHUMBPATH") : null;
                        if (mediaPath != null || thumbnailData != null || thumbnailLocalPath != null || xmppThumbPath != null) {
                            media = new WhatsAppMedia(
                                    mediaPath,
                                    thumbnailData,
                                    thumbnailLocalPath,
                                    xmppThumbPath,
                                    hasVcardString ? rs.getString("ZVCARDSTRING") : null,
                                    hasFileSize ? rs.getLong("ZFILESIZE") : 0,
                                    hasTitle ? rs.getString("ZTITLE") : null
                            );
                        }
                    }

                    messages.add(new WhatsAppMessage(
                            rs.getLong("Z_PK"),
                            rs.getLong("ZCHATSESSION"),
                            rs.getInt("ZISFROMME") == 1,
                            rs.getInt("ZMESSAGETYPE"),
                            toInstant(rs.getDouble("ZMESSAGEDATE")),
                            rs.getString("ZFROMJID"),
                            hasPushName ? rs.getString("ZPUSHNAME") : null,
                            rs.getString("ZTEXT"),
                            hasStarred && rs.getInt("ZSTARRED") == 1,
                            media
                    ));
                }
            }
        }

        logger.debug("queryMessages(chatSession={}, limit={}, offset={}) returned {} messages",
                chatSessionId, limit, offset, messages.size());
        return messages;
    }

    private void logDatabaseStats(boolean hasChatSession) {
        try (Statement stmt = connection.createStatement()) {
            int totalSessions = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ZWACHATSESSION")) {
                if (rs.next()) totalSessions = rs.getInt(1);
            }

            int sessionsWithJid = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ZWACHATSESSION WHERE ZCONTACTJID IS NOT NULL AND ZCONTACTJID != ''")) {
                if (rs.next()) sessionsWithJid = rs.getInt(1);
            }

            if (hasChatSession) {
                int sessionsWithMessages = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT ZCHATSESSION) FROM ZWAMESSAGE")) {
                    if (rs.next()) sessionsWithMessages = rs.getInt(1);
                }
                int totalMessages = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ZWAMESSAGE")) {
                    if (rs.next()) totalMessages = rs.getInt(1);
                }
                logger.info("WhatsApp DB stats: {} total sessions, {} with JID, {} with messages, {} total messages",
                        totalSessions, sessionsWithJid, sessionsWithMessages, totalMessages);
            } else {
                logger.info("WhatsApp DB stats: {} total sessions, {} with JID (no ZCHATSESSION column in ZWAMESSAGE)",
                        totalSessions, sessionsWithJid);
            }
        } catch (SQLException e) {
            logger.warn("Failed to log database stats", e);
        }
    }

    public int countMessages(long chatSessionId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM ZWAMESSAGE WHERE ZCHATSESSION = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, chatSessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private static Instant toInstant(double coreDataTimestamp) {
        if (coreDataTimestamp == 0) return Instant.EPOCH;
        return Instant.ofEpochSecond((long) coreDataTimestamp + CORE_DATA_EPOCH_OFFSET);
    }

    public String getWhatsappDomain() {
        return whatsappDomain;
    }

    public void setWhatsappDomain(String domain) {
        this.whatsappDomain = domain;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Failed to close WhatsApp database", e);
        }
    }
}
