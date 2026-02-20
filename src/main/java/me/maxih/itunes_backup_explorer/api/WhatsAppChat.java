package me.maxih.itunes_backup_explorer.api;

import java.time.Instant;

public record WhatsAppChat(
        long id,
        String contactJid,
        String partnerName,
        int sessionType,
        int messageCount,
        Instant lastMessageDate,
        String lastMessagePreview,
        String latestPushName
) {
    public boolean isGroup() {
        if (contactJid != null) {
            String jidLower = contactJid.toLowerCase(java.util.Locale.ROOT);
            if (jidLower.contains("@g.us") || jidLower.contains("@newsletter")) return true;
        }
        return sessionType == 1;
    }

    public String displayName() {
        if (partnerName != null && !partnerName.isBlank()) return partnerName;
        if (latestPushName != null && !latestPushName.isBlank()) return latestPushName;
        if (contactJid != null) return "+" + contactJid.split("@")[0];
        return "Unknown";
    }
}
