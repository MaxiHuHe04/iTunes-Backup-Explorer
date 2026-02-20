package me.maxih.itunes_backup_explorer.api;

import java.time.Instant;

public record WhatsAppMessage(
        long id,
        long chatSessionId,
        boolean fromMe,
        int messageType,
        Instant messageDate,
        String fromJid,
        String pushName,
        String text,
        boolean starred,
        WhatsAppMedia media
) {
}
