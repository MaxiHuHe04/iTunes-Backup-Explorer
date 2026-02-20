package me.maxih.itunes_backup_explorer.util;

import com.dd.plist.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

public class DeviceService {

    private static final Path VENV_PATH = Path.of(System.getProperty("user.home"),
            ".config", "itunes-backup-explorer", "python-venv");
    static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");

    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);
    private static Boolean libimobiledeviceAvailable;
    private static Boolean pymobiledevice3Available;

    public static boolean isLibimobiledeviceAvailable() {
        if (libimobiledeviceAvailable == null) {
            libimobiledeviceAvailable = testCommand("idevice_id", "--help");
            logger.info(libimobiledeviceAvailable
                    ? "libimobiledevice detected"
                    : "libimobiledevice not found");
        }
        return libimobiledeviceAvailable;
    }

    public static boolean isPymobiledevice3Available() {
        if (pymobiledevice3Available == null) {
            if (isPortablePymd3Ready()) {
                pymobiledevice3Available = testCommand(portablePython(), "-c", "import pymobiledevice3");
            } else {
                pymobiledevice3Available = testCommand(venvPython(), "-c", "import pymobiledevice3");
            }
            logger.info(pymobiledevice3Available
                    ? "pymobiledevice3 detected"
                    : "pymobiledevice3 not found");
        }
        return pymobiledevice3Available;
    }

    public static boolean isBackupToolAvailable() {
        return isLibimobiledeviceAvailable() || isPymobiledevice3Available();
    }

    public static String venvPython() {
        String binDir = IS_WINDOWS ? "Scripts" : "bin";
        String exe = IS_WINDOWS ? "python.exe" : "python3";
        return VENV_PATH.resolve(binDir).resolve(exe).toString();
    }

    public static String venvCli() {
        String binDir = IS_WINDOWS ? "Scripts" : "bin";
        String exe = IS_WINDOWS ? "pymobiledevice3.exe" : "pymobiledevice3";
        return VENV_PATH.resolve(binDir).resolve(exe).toString();
    }

    public static String activePython() {
        if (isPortablePythonReady()) return portablePython();
        return venvPython();
    }

    public static String activeCli() {
        if (isPortablePymd3Ready()) return portableCli();
        return venvCli();
    }

    public static Optional<String> detectDevice() {
        if (isLibimobiledeviceAvailable()) {
            byte[] output = runCommand(5, "idevice_id", "-l");
            if (output != null) {
                String result = new String(output, StandardCharsets.UTF_8).trim();
                if (!result.isEmpty()) {
                    String[] lines = result.split("\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) return Optional.of(trimmed);
                    }
                }
            }
        }

        if (isPymobiledevice3Available()) {
            return detectDeviceViaPymd3();
        }

        return Optional.empty();
    }

    private static Optional<String> detectDeviceViaPymd3() {
        String script = String.join("\n",
                "from pymobiledevice3.usbmux import list_devices",
                "devices = list_devices()",
                "if devices:",
                "    print(devices[0].serial)"
        );
        byte[] output = runCommand(10, activePython(), "-c", script);
        if (output == null) return Optional.empty();

        String result = new String(output, StandardCharsets.UTF_8).trim();
        if (result.isEmpty()) return Optional.empty();
        return Optional.of(result.split("\n")[0].trim());
    }

    public static Optional<DeviceInfo> getDeviceInfo(String udid) {
        if (isLibimobiledeviceAvailable()) {
            Optional<DeviceInfo> info = getDeviceInfoViaLibimobiledevice(udid);
            if (info.isPresent()) return info;
        }

        if (isPymobiledevice3Available()) {
            return getDeviceInfoViaPymd3(udid);
        }

        return Optional.empty();
    }

    private static Optional<DeviceInfo> getDeviceInfoViaLibimobiledevice(String udid) {
        byte[] mainOutput = runCommand(10, "ideviceinfo", "-u", udid, "-x");
        if (mainOutput == null) return Optional.empty();

        NSDictionary mainDict = parsePlistDict(mainOutput);
        if (mainDict == null) return Optional.empty();

        byte[] batteryOutput = runCommand(10, "ideviceinfo", "-u", udid, "-q", "com.apple.mobile.battery", "-x");
        NSDictionary batteryDict = parsePlistDict(batteryOutput);

        byte[] diskOutput = runCommand(10, "ideviceinfo", "-u", udid, "-q", "com.apple.disk_usage", "-x");
        NSDictionary diskDict = parsePlistDict(diskOutput);

        int batteryLevel = 0;
        String batteryStatus = "";
        if (batteryDict != null) {
            batteryLevel = getInt(batteryDict, "BatteryCurrentCapacity", 0);
            boolean charging = getBool(batteryDict, "BatteryIsCharging", false);
            batteryStatus = charging ? "Charging" : "Discharging";
        }

        long totalDiskCapacity = -1;
        long totalDataCapacity = -1;
        long totalDataAvailable = -1;
        long totalMobileAppUsage = 0;
        long webAppCacheUsage = 0;
        long mediaCacheUsage = 0;
        if (diskDict != null) {
            totalDiskCapacity = getLong(diskDict, "TotalDiskCapacity", -1);
            totalDataCapacity = getLong(diskDict, "TotalDataCapacity", -1);
            totalDataAvailable = getLong(diskDict, "AmountDataAvailable", -1);
            webAppCacheUsage = getLong(diskDict, "WebAppCacheUsage", 0);
            mediaCacheUsage = getLong(diskDict, "MediaCacheUsage", 0);

            NSObject appUsageObj = diskDict.objectForKey("MobileApplicationUsage");
            if (appUsageObj instanceof NSDictionary appUsageDict) {
                for (String key : appUsageDict.allKeys()) {
                    NSObject val = appUsageDict.objectForKey(key);
                    if (val instanceof NSNumber num) {
                        totalMobileAppUsage += num.longValue();
                    }
                }
            }
        }

        DeviceInfo info = new DeviceInfo(
                getString(mainDict, "DeviceName", ""),
                getString(mainDict, "ModelNumber", ""),
                getString(mainDict, "ProductType", ""),
                getString(mainDict, "ProductVersion", ""),
                getString(mainDict, "BuildVersion", ""),
                getString(mainDict, "SerialNumber", ""),
                getString(mainDict, "UniqueDeviceID", ""),
                getString(mainDict, "PhoneNumber", ""),
                getString(mainDict, "WiFiAddress", ""),
                batteryLevel,
                batteryStatus,
                totalDiskCapacity,
                totalDataCapacity,
                totalDataAvailable,
                totalMobileAppUsage,
                webAppCacheUsage,
                mediaCacheUsage
        );

        return Optional.of(info);
    }

    private static Optional<DeviceInfo> getDeviceInfoViaPymd3(String udid) {
        String script = String.join("\n",
                "import json",
                "from pymobiledevice3.lockdown import create_using_usbmux",
                "l = create_using_usbmux(serial='" + udid.replace("'", "") + "')",
                "v = l.all_values",
                "disk = {}",
                "try:",
                "    disk = l.get_value(domain='com.apple.disk_usage') or {}",
                "except Exception:",
                "    pass",
                "bat = {}",
                "try:",
                "    bat = l.get_value(domain='com.apple.mobile.battery') or {}",
                "except Exception:",
                "    pass",
                "print(json.dumps({",
                "    'DeviceName': v.get('DeviceName', ''),",
                "    'ModelNumber': v.get('ModelNumber', ''),",
                "    'ProductType': v.get('ProductType', ''),",
                "    'ProductVersion': v.get('ProductVersion', ''),",
                "    'BuildVersion': v.get('BuildVersion', ''),",
                "    'SerialNumber': v.get('SerialNumber', ''),",
                "    'UniqueDeviceID': v.get('UniqueDeviceID', ''),",
                "    'PhoneNumber': v.get('PhoneNumber', ''),",
                "    'WiFiAddress': v.get('WiFiAddress', ''),",
                "    'BatteryCurrentCapacity': bat.get('BatteryCurrentCapacity', 0),",
                "    'BatteryIsCharging': bat.get('BatteryIsCharging', False),",
                "    'TotalDiskCapacity': disk.get('TotalDiskCapacity', -1),",
                "    'TotalDataCapacity': disk.get('TotalDataCapacity', -1),",
                "    'AmountDataAvailable': disk.get('AmountDataAvailable', -1),",
                "    'WebAppCacheUsage': disk.get('WebAppCacheUsage', 0),",
                "    'MediaCacheUsage': disk.get('MediaCacheUsage', 0),",
                "    'TotalMobileAppUsage': sum(v for v in (disk.get('MobileApplicationUsage') or {}).values() if isinstance(v, (int, float))),",
                "}))"
        );
        byte[] output = runCommand(30, activePython(), "-c", script);
        if (output == null) return Optional.empty();

        String result = new String(output, StandardCharsets.UTF_8).trim();
        String[] lines = result.split("\n");
        String jsonLine = lines[lines.length - 1].trim();

        try {
            java.util.Map<String, String> values = parseSimpleJson(jsonLine);
            if (values == null) return Optional.empty();

            int batteryLevel = 0;
            String batteryStatus = "";
            long totalDiskCapacity = -1;
            long totalDataCapacity = -1;
            long totalDataAvailable = -1;

            try { batteryLevel = Integer.parseInt(values.getOrDefault("BatteryCurrentCapacity", "0")); } catch (NumberFormatException ignored) {}
            boolean charging = "true".equalsIgnoreCase(values.getOrDefault("BatteryIsCharging", "false"))
                    || "True".equals(values.getOrDefault("BatteryIsCharging", "false"));
            batteryStatus = charging ? "Charging" : "Discharging";
            try { totalDiskCapacity = Long.parseLong(values.getOrDefault("TotalDiskCapacity", "-1")); } catch (NumberFormatException ignored) {}
            try { totalDataCapacity = Long.parseLong(values.getOrDefault("TotalDataCapacity", "-1")); } catch (NumberFormatException ignored) {}
            try { totalDataAvailable = Long.parseLong(values.getOrDefault("AmountDataAvailable", "-1")); } catch (NumberFormatException ignored) {}

            long totalMobileAppUsage = 0;
            long webAppCacheUsage = 0;
            long mediaCacheUsage = 0;
            try { totalMobileAppUsage = Long.parseLong(values.getOrDefault("TotalMobileAppUsage", "0")); } catch (NumberFormatException ignored) {}
            try { webAppCacheUsage = Long.parseLong(values.getOrDefault("WebAppCacheUsage", "0")); } catch (NumberFormatException ignored) {}
            try { mediaCacheUsage = Long.parseLong(values.getOrDefault("MediaCacheUsage", "0")); } catch (NumberFormatException ignored) {}

            DeviceInfo info = new DeviceInfo(
                    values.getOrDefault("DeviceName", ""),
                    values.getOrDefault("ModelNumber", ""),
                    values.getOrDefault("ProductType", ""),
                    values.getOrDefault("ProductVersion", ""),
                    values.getOrDefault("BuildVersion", ""),
                    values.getOrDefault("SerialNumber", ""),
                    values.getOrDefault("UniqueDeviceID", ""),
                    values.getOrDefault("PhoneNumber", ""),
                    values.getOrDefault("WiFiAddress", ""),
                    batteryLevel, batteryStatus,
                    totalDiskCapacity, totalDataCapacity, totalDataAvailable,
                    totalMobileAppUsage, webAppCacheUsage, mediaCacheUsage
            );
            return Optional.of(info);
        } catch (Exception e) {
            logger.warn("Failed to parse pymobiledevice3 device info: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static java.util.Map<String, String> parseSimpleJson(String json) {
        try {
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) return null;
            json = json.substring(1, json.length() - 1);

            java.util.Map<String, String> map = new java.util.HashMap<>();
            String[] pairs = json.split(",(?=\\s*\")");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String value = kv[1].trim().replaceAll("^\"|\"$", "");
                map.put(key, value);
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<DeviceApp> getApps(String udid, boolean system) {
        if (isLibimobiledeviceAvailable()) {
            List<DeviceApp> apps = getAppsViaLibimobiledevice(udid, system);
            if (!apps.isEmpty()) return apps;
        }

        if (isPymobiledevice3Available()) {
            return getAppsViaPymd3(udid, system);
        }

        return new ArrayList<>();
    }

    private static List<DeviceApp> getAppsViaLibimobiledevice(String udid, boolean system) {
        List<DeviceApp> apps = new ArrayList<>();

        String listOption = system ? "list_system" : "list_user";
        byte[] output = runCommand(15, "ideviceinstaller", "-u", udid, "-l", "-o", listOption, "-o", "xml");
        if (output == null) return apps;

        try {
            NSObject parsed = PropertyListParser.parse(output);
            if (!(parsed instanceof NSArray array)) return apps;

            String appType = system ? "System" : "User";
            for (NSObject item : array.getArray()) {
                if (!(item instanceof NSDictionary dict)) continue;

                String name = getString(dict, "CFBundleDisplayName", "");
                if (name.isEmpty()) name = getString(dict, "CFBundleName", "");

                String bundleId = getString(dict, "CFBundleIdentifier", "");

                String version = getString(dict, "CFBundleShortVersionString", "");
                if (version.isEmpty()) version = getString(dict, "CFBundleVersion", "");

                apps.add(new DeviceApp(name, bundleId, version, appType));
            }
        } catch (Exception e) {
            logger.warn("Failed to parse app list: {}", e.getMessage());
        }

        return apps;
    }

    private static List<DeviceApp> getAppsViaPymd3(String udid, boolean system) {
        List<DeviceApp> apps = new ArrayList<>();
        String appType = system ? "System" : "User";
        String script = String.join("\n",
                "import json",
                "from pymobiledevice3.lockdown import create_using_usbmux",
                "from pymobiledevice3.services.installation_proxy import InstallationProxyService",
                "l = create_using_usbmux(serial='" + udid.replace("'", "") + "')",
                "svc = InstallationProxyService(lockdown=l)",
                "apps = svc.get_apps(application_type='" + (system ? "System" : "User") + "')",
                "result = []",
                "for bid, info in apps.items():",
                "    name = info.get('CFBundleDisplayName', '') or info.get('CFBundleName', '')",
                "    version = info.get('CFBundleShortVersionString', '') or info.get('CFBundleVersion', '')",
                "    result.append({'name': name, 'bundleId': bid, 'version': version})",
                "print(json.dumps(result))"
        );
        byte[] output = runCommand(30, activePython(), "-c", script);
        if (output == null) return apps;

        String result = new String(output, StandardCharsets.UTF_8).trim();
        String[] lines = result.split("\n");
        String jsonLine = lines[lines.length - 1].trim();

        try {
            if (!jsonLine.startsWith("[")) return apps;
            // Parse JSON array manually
            jsonLine = jsonLine.substring(1, jsonLine.length() - 1);
            if (jsonLine.isBlank()) return apps;

            // Split by },{ pattern
            String[] items = jsonLine.split("\\},\\s*\\{");
            for (String item : items) {
                item = item.replaceAll("^\\{|\\}$", "");
                java.util.Map<String, String> map = parseSimpleJson("{" + item + "}");
                if (map == null) continue;
                apps.add(new DeviceApp(
                        map.getOrDefault("name", ""),
                        map.getOrDefault("bundleId", ""),
                        map.getOrDefault("version", ""),
                        appType
                ));
            }
        } catch (Exception e) {
            logger.warn("Failed to parse pymobiledevice3 app list: {}", e.getMessage());
        }

        return apps;
    }

    public static boolean uninstallApp(String udid, String bundleId) {
        if (isLibimobiledeviceAvailable()) {
            byte[] result = runCommand(30, "ideviceinstaller", "-u", udid, "-U", bundleId);
            if (result != null) return true;
        }

        if (isPymobiledevice3Available()) {
            String script = String.join("\n",
                    "from pymobiledevice3.lockdown import create_using_usbmux",
                    "from pymobiledevice3.services.installation_proxy import InstallationProxyService",
                    "l = create_using_usbmux(serial='" + udid.replace("'", "") + "')",
                    "svc = InstallationProxyService(lockdown=l)",
                    "svc.uninstall('" + bundleId.replace("'", "") + "')",
                    "print('OK')"
            );
            byte[] output = runCommand(30, activePython(), "-c", script);
            if (output != null) {
                return new String(output, StandardCharsets.UTF_8).trim().contains("OK");
            }
        }

        return false;
    }

    public static boolean takeScreenshot(String udid, File output) {
        byte[] result = runCommand(15, "idevicescreenshot", "-u", udid, output.getAbsolutePath());
        if (result == null) return false;
        return output.exists();
    }

    public static boolean restartDevice(String udid) {
        return runDiagnostics(udid, "restart");
    }

    public static boolean shutdownDevice(String udid) {
        return runDiagnostics(udid, "shutdown");
    }

    public static boolean sleepDevice(String udid) {
        return runDiagnostics(udid, "sleep");
    }

    private static boolean runDiagnostics(String udid, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("idevicediagnostics", "-u", udid, command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.warn("Failed to run diagnostic '{}': {}", command, e.getMessage());
            return false;
        }
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

    private record CommandResult(int exitCode, String output) {}

    private static CommandResult runCommandWithLog(Consumer<String> onProgressLine, String... command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (onProgressLine != null) {
                        emitLog(onProgressLine, "[encryption] " + line);
                    }
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, output.toString());
            }
            return new CommandResult(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, output.toString());
        } catch (Exception e) {
            logger.warn("Failed to run command '{}': {}", command[0], e.getMessage());
            return new CommandResult(-1, output.toString());
        }
    }

    private static byte[] runCommand(int timeoutSeconds, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0) return null;

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.warn("Failed to run command '{}': {}", command[0], e.getMessage());
            return null;
        }
    }

    private static NSDictionary parsePlistDict(byte[] data) {
        if (data == null) return null;
        try {
            NSObject parsed = PropertyListParser.parse(data);
            if (parsed instanceof NSDictionary dict) return dict;
        } catch (Exception e) {
            logger.warn("Failed to parse plist: {}", e.getMessage());
        }
        return null;
    }

    private static String getString(NSDictionary dict, String key, String defaultValue) {
        NSObject obj = dict.objectForKey(key);
        if (obj instanceof NSString nsString) return nsString.getContent();
        return defaultValue;
    }

    private static int getInt(NSDictionary dict, String key, int defaultValue) {
        NSObject obj = dict.objectForKey(key);
        if (obj instanceof NSNumber nsNumber) return nsNumber.intValue();
        return defaultValue;
    }

    private static long getLong(NSDictionary dict, String key, long defaultValue) {
        NSObject obj = dict.objectForKey(key);
        if (obj instanceof NSNumber nsNumber) return nsNumber.longValue();
        return defaultValue;
    }

    private static boolean getBool(NSDictionary dict, String key, boolean defaultValue) {
        NSObject obj = dict.objectForKey(key);
        if (obj instanceof NSNumber nsNumber) return nsNumber.boolValue();
        return defaultValue;
    }

    private static final String PORTABLE_PYTHON_VERSION = "3.12.8";
    private static final String PORTABLE_PYTHON_URL =
            "https://www.python.org/ftp/python/" + PORTABLE_PYTHON_VERSION
                    + "/python-" + PORTABLE_PYTHON_VERSION + "-embed-amd64.zip";
    private static final String GET_PIP_URL = "https://bootstrap.pypa.io/get-pip.py";

    private static final Path PORTABLE_PYTHON_PATH = VENV_PATH.resolveSibling("python-portable");

    public static String findSystemPython() {
        for (String candidate : IS_WINDOWS
                ? new String[]{"python", "python3", "py"}
                : new String[]{"python3", "python"}) {
            if (testCommand(candidate, "--version")) return candidate;
        }
        return null;
    }

    private static String portablePython() {
        return PORTABLE_PYTHON_PATH.resolve("python.exe").toString();
    }

    private static String portablePip() {
        return PORTABLE_PYTHON_PATH.resolve("Scripts").resolve("pip.exe").toString();
    }

    private static String portableCli() {
        return PORTABLE_PYTHON_PATH.resolve("Scripts").resolve("pymobiledevice3.exe").toString();
    }

    public static boolean isVenvReady() {
        return Files.isExecutable(Path.of(venvPython()));
    }

    private static String venvPip() {
        String binDir = IS_WINDOWS ? "Scripts" : "bin";
        String exe = IS_WINDOWS ? "pip.exe" : "pip";
        return VENV_PATH.resolve(binDir).resolve(exe).toString();
    }

    public static boolean isPortablePythonReady() {
        return IS_WINDOWS && Files.isExecutable(PORTABLE_PYTHON_PATH.resolve("python.exe"));
    }

    public static boolean isPortablePymd3Ready() {
        return IS_WINDOWS && Files.isExecutable(PORTABLE_PYTHON_PATH.resolve("Scripts").resolve("pymobiledevice3.exe"));
    }

    public static void setupPymobiledevice3(Consumer<String> onProgressLine,
                                             Runnable onDone, Consumer<String> onError) {
        Thread setupThread = new Thread(() -> {
            try {
                if (IS_WINDOWS) {
                    setupOnWindows(onProgressLine);
                } else {
                    setupOnUnix(onProgressLine);
                }
                pymobiledevice3Available = null;
                javafx.application.Platform.runLater(onDone);
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        });
        setupThread.setName("pymobiledevice3-setup");
        setupThread.setDaemon(true);
        setupThread.start();
    }

    private static void setupOnUnix(Consumer<String> log) throws IOException, InterruptedException {
        String python = findSystemPython();
        if (python == null) {
            throw new IOException("Python not found. Install Python 3.8+ via your package manager.");
        }
        runSetupStep(log, python, "-m", "venv", VENV_PATH.toString());
        runSetupStep(log, venvPip(), "install", "--upgrade", "pip");
        runSetupStep(log, venvPip(), "install", "pymobiledevice3");
    }

    private static void setupOnWindows(Consumer<String> log) throws IOException, InterruptedException {
        String systemPython = findSystemPython();

        if (systemPython != null) {
            emitLog(log, "Using system Python: " + systemPython);
            runSetupStep(log, systemPython, "-m", "venv", VENV_PATH.toString());
            runSetupStep(log, venvPip(), "install", "--upgrade", "pip");
            runSetupStep(log, venvPip(), "install", "pymobiledevice3");
            return;
        }

        emitLog(log, "Python not found on system. Downloading portable Python " + PORTABLE_PYTHON_VERSION + "...");
        Files.createDirectories(PORTABLE_PYTHON_PATH);

        Path zipFile = PORTABLE_PYTHON_PATH.resolve("python-embed.zip");
        downloadFile(PORTABLE_PYTHON_URL, zipFile, log);

        emitLog(log, "Extracting Python...");
        extractZip(zipFile, PORTABLE_PYTHON_PATH);
        Files.deleteIfExists(zipFile);

        enablePipSupport();

        Path getPipPy = PORTABLE_PYTHON_PATH.resolve("get-pip.py");
        emitLog(log, "Downloading pip installer...");
        downloadFile(GET_PIP_URL, getPipPy, log);

        emitLog(log, "Installing pip...");
        runSetupStep(log, portablePython(), getPipPy.toString(), "--no-warn-script-location");
        Files.deleteIfExists(getPipPy);

        emitLog(log, "Installing build tools...");
        runSetupStep(log, portablePip(), "install", "setuptools", "wheel", "--no-warn-script-location");

        emitLog(log, "Installing pymobiledevice3...");
        runSetupStep(log, portablePip(), "install", "pymobiledevice3", "--no-warn-script-location");

        emitLog(log, "Setup complete.");
    }

    private static void enablePipSupport() throws IOException {
        try (var stream = Files.list(PORTABLE_PYTHON_PATH)) {
            stream.filter(p -> p.getFileName().toString().matches("python\\d+\\._pth"))
                    .findFirst()
                    .ifPresent(pthFile -> {
                        try {
                            String content = Files.readString(pthFile, StandardCharsets.UTF_8);
                            String updated = content.replace("#import site", "import site");
                            Files.writeString(pthFile, updated, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            logger.warn("Failed to patch _pth file: {}", e.getMessage());
                        }
                    });
        }
    }

    static void downloadFile(String urlStr, Path destination, Consumer<String> log) throws IOException {
        String currentUrl = urlStr;
        HttpURLConnection conn = null;

        for (int redirects = 0; redirects < 10; redirects++) {
            conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("Redirect without Location header from " + currentUrl);
                currentUrl = location;
                continue;
            }

            if (code != 200) {
                conn.disconnect();
                throw new IOException("Download failed (HTTP " + code + "): " + currentUrl);
            }
            break;
        }

        long totalSize = conn.getContentLengthLong();
        long downloaded = 0;
        int lastPercent = -1;

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                if (totalSize > 0) {
                    int percent = (int) (downloaded * 100 / totalSize);
                    if (percent != lastPercent && percent % 10 == 0) {
                        lastPercent = percent;
                        String msg = "Downloading... " + percent + "% (" + (downloaded / 1024) + " KB)";
                        emitLog(log, msg);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    static void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) continue;

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    static void extract7z(Path sevenZFile, Path targetDir) throws IOException {
        try (SevenZFile archive = SevenZFile.builder().setFile(sevenZFile.toFile()).get()) {
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) continue;

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = archive.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }

    private static void emitLog(Consumer<String> log, String message) {
        javafx.application.Platform.runLater(() -> log.accept(message));
    }

    private static void runSetupStep(Consumer<String> onProgressLine, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String finalLine = line;
                javafx.application.Platform.runLater(() -> onProgressLine.accept(finalLine));
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ": " + command[0]);
        }
    }

    public enum BackupResult { SUCCESS, CANCELLED, FAILED }

    public static boolean setBackupEncryption(String udid, char[] password, boolean enable, Consumer<String> onProgressLine) {
        if (password == null || password.length == 0) {
            emitLog(onProgressLine, "[encryption] Password is empty.");
            return false;
        }
        String pass = new String(password);
        String mode = enable ? "on" : "off";

        if (isPymobiledevice3Available()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(activeCli());
            cmd.add("backup2");
            cmd.add("encryption");
            cmd.add(mode);
            cmd.add(pass);
            cmd.add("--udid");
            cmd.add(udid);
            CommandResult res = runCommandWithLog(onProgressLine, cmd.toArray(new String[0]));
            if (res.exitCode == 0) return true;
            emitLog(onProgressLine, "[encryption] pymobiledevice3 failed; trying libimobiledevice...");
            logger.warn("pymobiledevice3 encryption {} failed (exit={})", mode, res.exitCode);
        }

        if (isLibimobiledeviceAvailable()) {
            CommandResult res = runCommandWithLog(onProgressLine,
                    "idevicebackup2", "encryption", mode, pass, "--udid", udid);
            if (res.exitCode == 0) return true;
            logger.warn("libimobiledevice encryption {} failed (exit={})", mode, res.exitCode);
        }

        return false;
    }

    public static BackupResult createBackup(String udid, File destination,
                                            Consumer<String> onProgressLine, Supplier<Boolean> isCancelled) {
        if (isPymobiledevice3Available()) {
            logger.info("Creating backup via pymobiledevice3");
            BackupResult result = BackupResult.FAILED;
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (attempt > 1) {
                    emitLog(onProgressLine, "pymobiledevice3 failed; retrying (" + attempt + "/2)...");
                    logger.warn("pymobiledevice3 backup failed; retrying attempt {}/2", attempt);
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                result = createBackupViaPymd3(udid, destination, onProgressLine, isCancelled);
                if (result == BackupResult.SUCCESS || result == BackupResult.CANCELLED) return result;
            }
            if (isLibimobiledeviceAvailable()) {
                emitLog(onProgressLine, "pymobiledevice3 failed; retrying with libimobiledevice...");
                logger.warn("pymobiledevice3 backup failed; falling back to libimobiledevice");
                return createBackupViaLibimobiledevice(udid, destination, onProgressLine, isCancelled);
            }
            return result;
        }

        if (isLibimobiledeviceAvailable()) {
            logger.info("Creating backup via libimobiledevice");
            return createBackupViaLibimobiledevice(udid, destination, onProgressLine, isCancelled);
        }

        return BackupResult.FAILED;
    }

    private static BackupResult createBackupViaLibimobiledevice(String udid, File destination,
                                                                 Consumer<String> onProgressLine, Supplier<Boolean> isCancelled) {
        BackupResult result = runIdevicebackup(udid, destination, onProgressLine, isCancelled, true);
        if (result == BackupResult.SUCCESS || result == BackupResult.CANCELLED) return result;

        emitLog(onProgressLine, "libimobiledevice --full failed; retrying without --full...");
        logger.warn("libimobiledevice --full failed; retrying without --full");
        return runIdevicebackup(udid, destination, onProgressLine, isCancelled, false);
    }

    private static BackupResult runIdevicebackup(String udid, File destination,
                                                 Consumer<String> onProgressLine, Supplier<Boolean> isCancelled,
                                                 boolean full) {
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("idevicebackup2");
            cmd.add("backup");
            if (full) cmd.add("--full");
            cmd.add("--udid");
            cmd.add(udid);
            cmd.add(destination.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            Process proc = process;

            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        onProgressLine.accept(line);
                    }
                } catch (IOException ignored) {
                }
            }, "idevice-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            while (readerThread.isAlive()) {
                if (isCancelled.get()) {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    return BackupResult.CANCELLED;
                }
                try {
                    readerThread.join(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    return BackupResult.CANCELLED;
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return BackupResult.FAILED;
            }
            return process.exitValue() == 0 ? BackupResult.SUCCESS : BackupResult.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return BackupResult.CANCELLED;
        } catch (Exception e) {
            logger.warn("Failed to create backup via libimobiledevice: {}", e.getMessage());
            return BackupResult.FAILED;
        }
    }

    private static BackupResult createBackupViaPymd3(String udid, File destination,
                                                      Consumer<String> onProgressLine, Supplier<Boolean> isCancelled) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    activeCli(), "backup2", "backup", "--full", "--udid", udid, destination.getAbsolutePath());
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(true);
            process = pb.start();
            Process proc = process;

            Thread readerThread = new Thread(() -> {
                try (InputStreamReader isr = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)) {
                    StringBuilder sb = new StringBuilder();
                    int ch;
                    while ((ch = isr.read()) != -1) {
                        if (ch == '\r' || ch == '\n') {
                            if (!sb.isEmpty()) {
                                onProgressLine.accept(sb.toString());
                                sb.setLength(0);
                            }
                        } else {
                            sb.append((char) ch);
                        }
                    }
                    if (!sb.isEmpty()) {
                        onProgressLine.accept(sb.toString());
                    }
                } catch (IOException ignored) {
                }
            }, "pymd3-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            while (readerThread.isAlive()) {
                if (isCancelled.get()) {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    return BackupResult.CANCELLED;
                }
                try {
                    readerThread.join(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    return BackupResult.CANCELLED;
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return BackupResult.FAILED;
            }
            return process.exitValue() == 0 ? BackupResult.SUCCESS : BackupResult.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return BackupResult.CANCELLED;
        } catch (Exception e) {
            logger.warn("Failed to create backup via pymobiledevice3: {}", e.getMessage());
            return BackupResult.FAILED;
        }
    }

    private DeviceService() {}
}
