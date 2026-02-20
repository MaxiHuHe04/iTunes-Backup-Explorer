package me.maxih.itunes_backup_explorer.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import me.maxih.itunes_backup_explorer.api.BackupFile;
import me.maxih.itunes_backup_explorer.util.MediaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ThumbnailService {
    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);

    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "tiff"
    );

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mov", "mp4", "m4v", "avi"
    );

    private static final ThumbnailService INSTANCE = new ThumbnailService();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "thumbnail-loader");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Image> cache = new ConcurrentHashMap<>();

    private static Image photoPlaceholder;
    private static Image videoPlaceholder;

    public static ThumbnailService getInstance() {
        return INSTANCE;
    }

    public static Image getPhotoPlaceholder() {
        if (photoPlaceholder == null) {
            photoPlaceholder = createPlaceholder(Color.web("#4a5568"), Color.web("#a0aec0"));
        }
        return photoPlaceholder;
    }

    public static Image getVideoPlaceholder() {
        if (videoPlaceholder == null) {
            videoPlaceholder = createPlaceholder(Color.web("#553c7b"), Color.web("#b794f4"));
        }
        return videoPlaceholder;
    }

    private static Image createPlaceholder(Color bgColor, Color fgColor) {
        int size = 64;
        WritableImage img = new WritableImage(size, size);
        var writer = img.getPixelWriter();

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                writer.setColor(x, y, bgColor);
            }
        }

        int cx = size / 2;
        int cy = size / 2;
        int r = 12;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                    writer.setColor(x, y, fgColor);
                }
            }
        }

        return img;
    }

    public static boolean isVideo(String extension) {
        return extension != null && VIDEO_EXTENSIONS.contains(extension.toLowerCase());
    }

    public static boolean isSupportedImage(String extension) {
        return extension != null && SUPPORTED_IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }

    public void loadThumbnail(BackupFile file, int size, Consumer<Image> onLoaded) {
        Image cached = cache.get(file.fileID);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }

        executor.submit(() -> {
            File tempFile = null;
            File convertedFile = null;
            try {
                String ext = file.getFileExtension();
                boolean nativeSupport = isSupportedImage(ext);
                boolean convertible = MediaConverter.needsConversion(ext);

                if (!nativeSupport && !convertible) {
                    Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                    cache.put(file.fileID, placeholder);
                    Platform.runLater(() -> onLoaded.accept(placeholder));
                    return;
                }

                tempFile = Files.createTempFile("thumb_", "." + ext).toFile();
                tempFile.deleteOnExit();
                file.extract(tempFile, false);

                File imageFile = tempFile;
                if (!nativeSupport && convertible) {
                    convertedFile = MediaConverter.convertToJpeg(tempFile, ext, size);
                    if (convertedFile == null) {
                        Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                        cache.put(file.fileID, placeholder);
                        Platform.runLater(() -> onLoaded.accept(placeholder));
                        return;
                    }
                    imageFile = convertedFile;
                }

                try (InputStream is = new FileInputStream(imageFile)) {
                    Image thumb = new Image(is, size, size, true, true);
                    if (!thumb.isError()) {
                        cache.put(file.fileID, thumb);
                        Platform.runLater(() -> onLoaded.accept(thumb));
                    } else {
                        Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                        cache.put(file.fileID, placeholder);
                        Platform.runLater(() -> onLoaded.accept(placeholder));
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to generate thumbnail for {}: {}", file.relativePath, e.getMessage());
                String ext = file.getFileExtension();
                Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                cache.put(file.fileID, placeholder);
                Platform.runLater(() -> onLoaded.accept(placeholder));
            } finally {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                if (convertedFile != null && convertedFile.exists()) convertedFile.delete();
            }
        });
    }

    public void loadPreview(BackupFile file, int maxSize, Consumer<Image> onLoaded) {
        executor.submit(() -> {
            File tempFile = null;
            File convertedFile = null;
            try {
                String ext = file.getFileExtension();
                boolean nativeSupport = isSupportedImage(ext);
                boolean convertible = MediaConverter.needsConversion(ext);

                if (!nativeSupport && !convertible) {
                    Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                    Platform.runLater(() -> onLoaded.accept(placeholder));
                    return;
                }

                tempFile = Files.createTempFile("preview_", "." + ext).toFile();
                tempFile.deleteOnExit();
                file.extract(tempFile, false);

                File imageFile = tempFile;
                if (!nativeSupport && convertible) {
                    convertedFile = MediaConverter.convertToJpeg(tempFile, ext, maxSize);
                    if (convertedFile == null) {
                        Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                        Platform.runLater(() -> onLoaded.accept(placeholder));
                        return;
                    }
                    imageFile = convertedFile;
                }

                try (InputStream is = new FileInputStream(imageFile)) {
                    Image preview = new Image(is, maxSize, maxSize, true, true);
                    if (!preview.isError()) {
                        Platform.runLater(() -> onLoaded.accept(preview));
                    } else {
                        Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                        Platform.runLater(() -> onLoaded.accept(placeholder));
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to generate preview for {}: {}", file.relativePath, e.getMessage());
                String ext = file.getFileExtension();
                Image placeholder = isVideo(ext) ? getVideoPlaceholder() : getPhotoPlaceholder();
                Platform.runLater(() -> onLoaded.accept(placeholder));
            } finally {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                if (convertedFile != null && convertedFile.exists()) convertedFile.delete();
            }
        });
    }

    public void clearCache() {
        cache.clear();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
