package me.maxih.itunes_backup_explorer.api;

public record WhatsAppMedia(
        String localPath,
        byte[] thumbnailData,
        String thumbnailLocalPath,
        String xmppThumbPath,
        String mimeType,
        long fileSize,
        String caption
) {
}
