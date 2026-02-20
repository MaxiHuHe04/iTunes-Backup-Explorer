package me.maxih.itunes_backup_explorer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class MediaConverter {
    private static final Logger logger = LoggerFactory.getLogger(MediaConverter.class);

    private static final Set<String> HEIF_EXTENSIONS = Set.of("heic", "heif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mov", "mp4", "m4v", "avi");

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"),
            ".config", "itunes-backup-explorer");
    private static final Path PORTABLE_FFMPEG_DIR = CONFIG_DIR.resolve("ffmpeg-portable");
    private static final Path PORTABLE_IMAGEMAGICK_DIR = CONFIG_DIR.resolve("imagemagick-portable");

    private static final String FFMPEG_DOWNLOAD_URL =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private static Boolean ffmpegAvailable;
    private static Boolean imageMagickAvailable;
    private static String effectiveFfmpegPath;
    private static String effectiveImageMagickPath;

    public static boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) {
            effectiveFfmpegPath = resolveFfmpeg();
            ffmpegAvailable = effectiveFfmpegPath != null;
            if (ffmpegAvailable) {
                logger.info("ffmpeg detected at {} - video thumbnails enabled", effectiveFfmpegPath);
            } else {
                logger.info("ffmpeg not found - videos will use placeholders");
            }
        }
        return ffmpegAvailable;
    }

    public static boolean isImageMagickAvailable() {
        if (imageMagickAvailable == null) {
            effectiveImageMagickPath = resolveImageMagick();
            imageMagickAvailable = effectiveImageMagickPath != null;
            if (imageMagickAvailable) {
                logger.info("ImageMagick detected at {} - HEIC conversion enabled", effectiveImageMagickPath);
            } else {
                logger.info("ImageMagick not found - HEIC photos will try ffmpeg fallback");
            }
        }
        return imageMagickAvailable;
    }

    private static String resolveFfmpeg() {
        if (DeviceService.IS_WINDOWS) {
            Path bundled = getBundledMediaToolsDir();
            if (bundled != null) {
                Path found = findExecutable(bundled.resolve("ffmpeg-portable"), "ffmpeg.exe");
                if (found != null) return found.toString();
            }
            Path portable = findExecutable(PORTABLE_FFMPEG_DIR, "ffmpeg.exe");
            if (portable != null) return portable.toString();
        }
        if (testCommand("ffmpeg", "-version")) return "ffmpeg";
        return null;
    }

    private static String resolveImageMagick() {
        if (DeviceService.IS_WINDOWS) {
            Path bundled = getBundledMediaToolsDir();
            if (bundled != null) {
                Path found = findExecutable(bundled.resolve("imagemagick-portable"), "magick.exe");
                if (found != null) return found.toString();
            }
            Path portable = findExecutable(PORTABLE_IMAGEMAGICK_DIR, "magick.exe");
            if (portable != null) return portable.toString();
        }
        if (testCommand("magick", "-version")) return "magick";
        if (testCommand("convert", "-version")) return "convert";
        return null;
    }

    private static Path getBundledMediaToolsDir() {
        try {
            Path codePath = Path.of(MediaConverter.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return codePath.getParent().resolve("media-tools");
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findExecutable(Path dir, String execName) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> walk = Files.walk(dir, 5)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(execName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isMediaToolsNeeded() {
        return DeviceService.IS_WINDOWS && (!isFfmpegAvailable() || !isImageMagickAvailable());
    }

    public static void resetDetection() {
        ffmpegAvailable = null;
        imageMagickAvailable = null;
        effectiveFfmpegPath = null;
        effectiveImageMagickPath = null;
    }

    public static void setupMediaTools(Consumer<String> onProgressLine,
                                        Runnable onDone, Consumer<String> onError) {
        Thread setupThread = new Thread(() -> {
            try {
                if (!isFfmpegAvailable()) setupFfmpeg(onProgressLine);
                if (!isImageMagickAvailable()) setupImageMagick(onProgressLine);
                resetDetection();
                javafx.application.Platform.runLater(onDone);
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        });
        setupThread.setName("media-tools-setup");
        setupThread.setDaemon(true);
        setupThread.start();
    }

    private static void setupFfmpeg(Consumer<String> log) throws IOException {
        Files.createDirectories(PORTABLE_FFMPEG_DIR);
        Path zipFile = PORTABLE_FFMPEG_DIR.resolve("ffmpeg.zip");
        try {
            emitLog(log, "Downloading ffmpeg (~85 MB)...");
            DeviceService.downloadFile(FFMPEG_DOWNLOAD_URL, zipFile, log);
            emitLog(log, "Extracting ffmpeg...");
            DeviceService.extractZip(zipFile, PORTABLE_FFMPEG_DIR);
        } finally {
            Files.deleteIfExists(zipFile);
        }
        emitLog(log, "ffmpeg installed successfully.");
    }

    private static void setupImageMagick(Consumer<String> log) throws IOException {
        Files.createDirectories(PORTABLE_IMAGEMAGICK_DIR);
        Path archiveFile = PORTABLE_IMAGEMAGICK_DIR.resolve("imagemagick.7z");
        try {
            emitLog(log, "Resolving latest ImageMagick version...");
            String url = resolveLatestImageMagickUrl();
            emitLog(log, "Downloading ImageMagick (~20 MB)...");
            DeviceService.downloadFile(url, archiveFile, log);
            emitLog(log, "Extracting ImageMagick...");
            DeviceService.extract7z(archiveFile, PORTABLE_IMAGEMAGICK_DIR);
        } finally {
            Files.deleteIfExists(archiveFile);
        }
        emitLog(log, "ImageMagick installed successfully.");
    }

    private static String resolveLatestImageMagickUrl() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://github.com/ImageMagick/ImageMagick/releases/latest").openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestMethod("HEAD");

        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        conn.disconnect();

        if (location == null) {
            throw new IOException("Failed to resolve ImageMagick latest version (HTTP " + code + ")");
        }

        String version = location.substring(location.lastIndexOf('/') + 1);
        return "https://github.com/ImageMagick/ImageMagick/releases/download/"
                + version + "/ImageMagick-" + version + "-portable-Q16-HDRI-x64.7z";
    }

    private static void emitLog(Consumer<String> log, String message) {
        javafx.application.Platform.runLater(() -> log.accept(message));
    }

    private static boolean testCommand(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isHeif(String extension) {
        return extension != null && HEIF_EXTENSIONS.contains(extension.toLowerCase());
    }

    public static boolean isVideo(String extension) {
        return extension != null && VIDEO_EXTENSIONS.contains(extension.toLowerCase());
    }

    public static boolean needsConversion(String extension) {
        return isHeif(extension) || isVideo(extension);
    }

    public static File convertToJpeg(File source, String extension, int maxSize) throws IOException {
        if (isHeif(extension)) {
            return convertHeifToJpeg(source, maxSize);
        }
        if (isVideo(extension)) {
            return extractVideoFrame(source, maxSize);
        }
        return null;
    }

    private static File convertHeifToJpeg(File source, int maxSize) throws IOException {
        if (isImageMagickAvailable()) {
            File result = convertHeifViaImageMagick(source, maxSize);
            if (result != null) return result;
        }
        if (isFfmpegAvailable()) {
            File result = convertHeifViaFfmpeg(source, maxSize);
            if (result != null) return result;
        }
        return null;
    }

    private static File convertHeifViaImageMagick(File source, int maxSize) throws IOException {
        File output = Files.createTempFile("heic_", ".jpg").toFile();
        output.deleteOnExit();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    effectiveImageMagickPath,
                    source.getAbsolutePath(),
                    "-resize", maxSize + "x" + maxSize + ">",
                    "-quality", "90",
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                output.delete();
                return null;
            }

            if (process.exitValue() != 0 || !output.exists() || output.length() == 0) {
                output.delete();
                return null;
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.delete();
            return null;
        } catch (Exception e) {
            logger.warn("Failed to convert HEIC via ImageMagick {}: {}", source.getName(), e.getMessage());
            output.delete();
            return null;
        }
    }

    private static File convertHeifViaFfmpeg(File source, int maxSize) throws IOException {
        File output = Files.createTempFile("heic_", ".jpg").toFile();
        output.deleteOnExit();

        try {
            String scale = "scale='min(" + maxSize + ",iw)':'min(" + maxSize + ",ih)':force_original_aspect_ratio=decrease";
            ProcessBuilder pb = new ProcessBuilder(
                    effectiveFfmpegPath, "-y",
                    "-i", source.getAbsolutePath(),
                    "-vf", scale,
                    "-q:v", "2",
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                output.delete();
                return null;
            }

            if (process.exitValue() != 0 || !output.exists() || output.length() == 0) {
                output.delete();
                return null;
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.delete();
            return null;
        } catch (Exception e) {
            logger.warn("Failed to convert HEIC via ffmpeg {}: {}", source.getName(), e.getMessage());
            output.delete();
            return null;
        }
    }

    private static File extractVideoFrame(File source, int maxSize) throws IOException {
        if (!isFfmpegAvailable()) return null;

        File output = Files.createTempFile("frame_", ".jpg").toFile();
        output.deleteOnExit();

        try {
            String scale = "scale='min(" + maxSize + ",iw)':'min(" + maxSize + ",ih)':force_original_aspect_ratio=decrease";
            ProcessBuilder pb = new ProcessBuilder(
                    effectiveFfmpegPath, "-y",
                    "-i", source.getAbsolutePath(),
                    "-ss", "00:00:00.5",
                    "-frames:v", "1",
                    "-vf", scale,
                    "-q:v", "2",
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                output.delete();
                return null;
            }

            if (process.exitValue() != 0 || !output.exists() || output.length() == 0) {
                output.delete();
                return null;
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.delete();
            return null;
        } catch (Exception e) {
            logger.warn("Failed to extract frame from {}: {}", source.getName(), e.getMessage());
            output.delete();
            return null;
        }
    }

    public static void detectTools() {
        isFfmpegAvailable();
        isImageMagickAvailable();
    }
}
