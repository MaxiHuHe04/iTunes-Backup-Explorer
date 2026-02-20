package me.maxih.itunes_backup_explorer.util;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MirrorService {

    public enum State {
        SETUP_REQUIRED, DISCONNECTED, CONNECTING, VIEW_ONLY, INTERACTIVE, ERROR
    }

    public enum DeviceReadiness {
        READY, DEVELOPER_MODE_DISABLED, NEEDS_TUNNEL, CHECK_FAILED
    }

    private static final Logger logger = LoggerFactory.getLogger(MirrorService.class);

    private static final int CONNECTING_TIMEOUT_SECONDS = 30;

    private Process streamProcess;
    private Thread frameReaderThread;
    private Thread stderrReaderThread;
    private ScheduledExecutorService wdaProbeExecutor;
    private ScheduledFuture<?> connectingTimeoutFuture;
    private ExecutorService httpExecutor = Executors.newCachedThreadPool();

    private volatile State state = State.SETUP_REQUIRED;
    private volatile String errorMessage = "";
    private volatile boolean airplayMode = false;
    private Consumer<State> stateListener;
    private int screenWidth;
    private int screenHeight;

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isVenvReady() {
        return DeviceService.isPymobiledevice3Available();
    }

    public DeviceReadiness checkDeviceReadiness(String udid) {
        String script = String.join("\n",
                "import logging",
                "logging.disable(logging.CRITICAL)",
                "from pymobiledevice3.lockdown import create_using_usbmux",
                "l = create_using_usbmux(serial='" + udid + "')",
                "if not l.developer_mode_status:",
                "    print('DEVELOPER_MODE_DISABLED')",
                "else:",
                "    v = int(l.product_version.split('.')[0])",
                "    if v >= 17:",
                "        print('NEEDS_TUNNEL')",
                "    else:",
                "        print('READY')"
        );

        try {
            Process p = new ProcessBuilder(
                    DeviceService.activePython(), "-c", script
            ).redirectErrorStream(true).start();

            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);

            if (!finished) {
                p.destroyForcibly();
                return DeviceReadiness.CHECK_FAILED;
            }

            return switch (output) {
                case "DEVELOPER_MODE_DISABLED" -> DeviceReadiness.DEVELOPER_MODE_DISABLED;
                case "NEEDS_TUNNEL" -> DeviceReadiness.NEEDS_TUNNEL;
                case "READY" -> DeviceReadiness.READY;
                default -> {
                    logger.warn("Unexpected readiness check result: {}", output);
                    yield DeviceReadiness.CHECK_FAILED;
                }
            };
        } catch (Exception e) {
            logger.warn("Failed to check readiness: {}", e.getMessage());
            return DeviceReadiness.CHECK_FAILED;
        }
    }

    public void revealDeveloperMode(String udid) {
        try {
            Process p = new ProcessBuilder(
                    DeviceService.activeCli(),
                    "amfi", "reveal-developer-mode", "--udid", udid
            ).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to reveal developer mode: {}", e.getMessage());
        }
    }

    private boolean isTunneldRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:49151/hello").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTunnelAvailable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:49151/").openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            return code == 200 && !body.equals("{}");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean requestTunnelForDevice(String udid) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://127.0.0.1:49151/start-tunnel?udid=" + udid
            ).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            return code == 200 && !body.contains("error");
        } catch (Exception e) {
            logger.warn("Failed to request tunnel for {}: {}", udid, e.getMessage());
            return false;
        }
    }

    public void ensureTunnelAndStart(String udid, Consumer<State> stateListenerArg, Consumer<byte[]> frameListener) {
        this.stateListener = stateListenerArg;
        setState(State.CONNECTING);

        Thread thread = new Thread(() -> {
            if (!isTunneldRunning()) {
                String cli = DeviceService.activeCli();
                try {
                    if (DeviceService.isWindows()) {
                        logger.info("Tunneld not detected, starting via PowerShell elevation...");
                        new ProcessBuilder(
                                "powershell", "-Command",
                                "Start-Process -Verb RunAs -WindowStyle Hidden -FilePath '"
                                        + cli + "' -ArgumentList 'remote tunneld --protocol tcp'"
                        ).start();
                    } else {
                        logger.info("Tunneld not detected, starting via pkexec...");
                        new ProcessBuilder(
                                "pkexec", cli,
                                "remote", "tunneld", "--protocol", "tcp", "--daemonize"
                        ).start();
                    }
                } catch (IOException e) {
                    logger.warn("Failed to start tunnel: {}", e.getMessage());
                    if (DeviceService.isWindows()) {
                        errorMessage = "Could not start tunnel. Run as Administrator:\n"
                                + cli + " remote tunneld --protocol tcp --daemonize";
                    } else {
                        errorMessage = "Could not start tunnel. Run manually:\nsudo "
                                + cli + " remote tunneld --protocol tcp --daemonize";
                    }
                    setState(State.ERROR);
                    return;
                }

                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (isTunneldRunning()) {
                        break;
                    }
                }

                if (!isTunneldRunning()) {
                    if (DeviceService.isWindows()) {
                        errorMessage = "Tunneld did not start. Accept the admin prompt and try again.\n"
                                + "Or run as Administrator: " + cli
                                + " remote tunneld --protocol tcp --daemonize";
                    } else {
                        errorMessage = "Tunneld did not start. Check that you authenticated correctly.\n"
                                + "Run manually: sudo " + cli
                                + " remote tunneld --protocol tcp --daemonize";
                    }
                    setState(State.ERROR);
                    return;
                }
            }

            logger.info("Tunneld running, requesting tunnel for {}...", udid);

            if (!isTunnelAvailable()) {
                requestTunnelForDevice(udid);

                for (int i = 0; i < 30; i++) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (isTunnelAvailable()) {
                        break;
                    }
                }
            }

            if (!isTunnelAvailable()) {
                errorMessage = "Tunnel was not created for the device. Check that the iPhone is unlocked and trusted this computer.";
                setState(State.ERROR);
                return;
            }

            logger.info("Tunnel available, starting stream...");
            Platform.runLater(() -> start(udid, stateListenerArg, frameListener));
        });
        thread.setName("mirror-tunnel-setup");
        thread.setDaemon(true);
        thread.start();
    }

    public void setup(Consumer<String> progressLog, Runnable onDone, Consumer<String> onError) {
        DeviceService.setupPymobiledevice3(progressLog, onDone, onError);
    }

    public boolean isUxplayAvailable() {
        if (!DeviceService.isWindows()) return true; // Linux: assume installed or will fail clearly
        return resolveUxplayPath() != null;
    }

    private static String resolveUxplayPath() {
        // Check common install locations
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\uxplay-windows\\uxplay.exe",
                System.getenv("ProgramFiles(x86)") + "\\uxplay-windows\\uxplay.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\uxplay-windows\\uxplay.exe",
        };
        for (String path : candidates) {
            if (path != null && Files.isExecutable(Path.of(path))) {
                return path;
            }
        }
        // Check PATH
        try {
            Process p = new ProcessBuilder("where", "uxplay").redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !output.isEmpty()) {
                return output.lines().findFirst().orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final String UXPLAY_DOWNLOAD_URL = "https://github.com/leapbtw/uxplay-windows/releases/latest";

    public void openUxplayDownloadPage() {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(UXPLAY_DOWNLOAD_URL));
        } catch (Exception e) {
            logger.warn("Failed to open browser: {}", e.getMessage());
        }
    }

    public void startAirPlay(Consumer<State> stateListenerArg, Consumer<byte[]> frameListener) {
        this.airplayMode = true;
        startStream(new String[]{"--airplay"}, stateListenerArg, frameListener);
    }

    public void start(String udid, Consumer<State> stateListenerArg, Consumer<byte[]> frameListener) {
        this.airplayMode = false;
        startStream(new String[]{udid}, stateListenerArg, frameListener);
    }

    private void startStream(String[] scriptArgs, Consumer<State> stateListenerArg, Consumer<byte[]> frameListener) {
        this.stateListener = stateListenerArg;

        if (streamProcess != null && streamProcess.isAlive()) {
            streamProcess.destroyForcibly();
        }
        killUxplay();
        if (frameReaderThread != null) frameReaderThread.interrupt();
        if (stderrReaderThread != null) stderrReaderThread.interrupt();
        if (wdaProbeExecutor != null && !wdaProbeExecutor.isShutdown()) wdaProbeExecutor.shutdownNow();

        try {
            InputStream resourceStream = getClass().getResourceAsStream("/me/maxih/itunes_backup_explorer/mirror_stream.py");
            Path tempScript = Files.createTempFile("mirror_stream_", ".py");
            if (resourceStream != null) {
                Files.copy(resourceStream, tempScript, StandardCopyOption.REPLACE_EXISTING);
            }
            tempScript.toFile().deleteOnExit();

            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(isVenvReady() ? DeviceService.activePython() : "python3");
            cmd.add("-u");
            cmd.add(tempScript.toString());
            for (String arg : scriptArgs) cmd.add(arg);
            ProcessBuilder pb = new ProcessBuilder(cmd);

            streamProcess = pb.start();
            setState(State.CONNECTING);

            AtomicReference<byte[]> latestFrame = new AtomicReference<>();
            AtomicBoolean renderPending = new AtomicBoolean(false);
            AtomicBoolean receivedFrame = new AtomicBoolean(false);

            frameReaderThread = new Thread(() -> {
                DataInputStream dis = new DataInputStream(streamProcess.getInputStream());
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int totalLength = dis.readInt();
                        if (totalLength <= 8) continue;

                        screenWidth = dis.readInt();
                        screenHeight = dis.readInt();

                        int imageLength = totalLength - 8;
                        byte[] imageData = new byte[imageLength];
                        dis.readFully(imageData);

                        latestFrame.set(imageData);
                        if (renderPending.compareAndSet(false, true)) {
                            Platform.runLater(() -> {
                                byte[] frame = latestFrame.getAndSet(null);
                                renderPending.set(false);
                                if (frame != null) {
                                    frameListener.accept(frame);
                                }
                            });
                        }

                        if (receivedFrame.compareAndSet(false, true)) {
                            cancelConnectingTimeout();
                            setState(State.VIEW_ONLY);
                        }
                    }
                } catch (EOFException e) {
                    logger.info("Frame stream ended");
                    if (!Thread.currentThread().isInterrupted() && (state == State.CONNECTING || state == State.VIEW_ONLY)) {
                        errorMessage = "Connection closed.";
                        setState(State.ERROR);
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        logger.warn("Error reading frames: {}", e.getMessage());
                    }
                }
            });
            frameReaderThread.setName("mirror-frame-reader");
            frameReaderThread.setDaemon(true);
            frameReaderThread.start();

            stderrReaderThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(streamProcess.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                        logger.info("mirror_stream: {}", line);
                        if (line.startsWith("MIRROR_ERROR:")) {
                            errorMessage = line.substring("MIRROR_ERROR:".length()).trim();
                            setState(State.ERROR);
                        } else if (line.equals("MIRROR_AIRPLAY_READY")) {
                            if (state == State.ERROR) {
                                setState(State.CONNECTING);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        logger.warn("Error reading stderr: {}", e.getMessage());
                    }
                }
            });
            stderrReaderThread.setName("mirror-stderr-reader");
            stderrReaderThread.setDaemon(true);
            stderrReaderThread.start();

            streamProcess.onExit().thenAccept(p -> {
                int exitCode = p.exitValue();
                if (state == State.CONNECTING) {
                    logger.warn("Python process exited with code {} during connection", exitCode);
                    if (errorMessage.isEmpty()) {
                        errorMessage = "Capture process exited unexpectedly (code " + exitCode + ")";
                    }
                    setState(State.ERROR);
                }
            });

            wdaProbeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mirror-wda-probe");
                t.setDaemon(true);
                return t;
            });

            if (!airplayMode) {
                connectingTimeoutFuture = wdaProbeExecutor.schedule(() -> {
                    if (state == State.CONNECTING) {
                        logger.warn("Timeout of {}s waiting for connection", CONNECTING_TIMEOUT_SECONDS);
                        errorMessage = "Connection timed out. Check that the device is unlocked and has developer mode enabled.";
                        setState(State.ERROR);
                        if (streamProcess != null && streamProcess.isAlive()) {
                            streamProcess.destroyForcibly();
                        }
                    }
                }, CONNECTING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            wdaProbeExecutor.scheduleAtFixedRate(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:8100/status").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestMethod("GET");
                    int responseCode = conn.getResponseCode();
                    conn.disconnect();
                    if (responseCode == 200) {
                        setState(State.INTERACTIVE);
                    }
                } catch (Exception e) {
                    if (state == State.INTERACTIVE) {
                        setState(State.VIEW_ONLY);
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);

        } catch (IOException e) {
            logger.error("Failed to start mirror process: {}", e.getMessage());
            errorMessage = e.getMessage();
            setState(State.ERROR);
        }
    }

    private void cancelConnectingTimeout() {
        if (connectingTimeoutFuture != null && !connectingTimeoutFuture.isDone()) {
            connectingTimeoutFuture.cancel(false);
        }
    }

    public void stop() {
        cancelConnectingTimeout();
        if (streamProcess != null) {
            streamProcess.destroy();
            try {
                if (!streamProcess.waitFor(3, TimeUnit.SECONDS)) {
                    streamProcess.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                streamProcess.destroyForcibly();
            }
        }
        killUxplay();
        if (frameReaderThread != null) {
            frameReaderThread.interrupt();
        }
        if (stderrReaderThread != null) {
            stderrReaderThread.interrupt();
        }
        if (wdaProbeExecutor != null && !wdaProbeExecutor.isShutdown()) {
            wdaProbeExecutor.shutdownNow();
        }
        setState(State.DISCONNECTED);
    }

    private void killUxplay() {
        try {
            if (DeviceService.isWindows()) {
                new ProcessBuilder("taskkill", "/F", "/IM", "uxplay.exe")
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            } else {
                new ProcessBuilder("pkill", "-9", "-f", "uxplay")
                        .start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
    }

    public void sendTap(double normX, double normY) {
        if (state != State.INTERACTIVE) return;

        double x = normX * screenWidth;
        double y = normY * screenHeight;
        String body = "{\"x\": " + x + ", \"y\": " + y + "}";

        httpExecutor.submit(() -> sendWdaPost("http://localhost:8100/wda/tap", body));
    }

    public void sendSwipe(double fromX, double fromY, double toX, double toY, long durationMs) {
        if (state != State.INTERACTIVE) return;

        double fx = fromX * screenWidth;
        double fy = fromY * screenHeight;
        double tx = toX * screenWidth;
        double ty = toY * screenHeight;
        double durationSeconds = durationMs / 1000.0;

        String body = "{\"fromX\": " + fx + ", \"fromY\": " + fy
                + ", \"toX\": " + tx + ", \"toY\": " + ty
                + ", \"duration\": " + durationSeconds + "}";

        httpExecutor.submit(() -> sendWdaPost("http://localhost:8100/wda/dragfromtoforduration", body));
    }

    private void sendWdaPost(String urlString, String jsonBody) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes());
            }
            conn.getInputStream().readAllBytes();
            conn.disconnect();
        } catch (Exception e) {
            logger.warn("Failed to send WDA command to {}: {}", urlString, e.getMessage());
        }
    }

    public void shutdown() {
        stop();
        if (httpExecutor != null && !httpExecutor.isShutdown()) {
            httpExecutor.shutdownNow();
        }
    }

    private void setState(State newState) {
        this.state = newState;
        if (stateListener != null) {
            Platform.runLater(() -> stateListener.accept(newState));
        }
    }
}
