# Changelog — iTunes Backup Explorer (Fork)

Documentation of all improvements implemented on top of the [original project](https://github.com/MaxiHuHe04/iTunes-Backup-Explorer) by [MaxiHuHe04](https://github.com/MaxiHuHe04).

The original project provided basic navigation of encrypted iTunes backups. This fork transforms it into a complete iPhone toolkit, adding screen mirroring, media gallery, live device control, backup creation, and a modern interface.

---

## Summary of changes

| Area | Before | After |
|------|--------|-------|
| Interface | Basic light theme, small window | Professional dark theme, 1200x700, status bar, drag & drop, shortcuts |
| Media | No visual support | Gallery with real thumbnails, filters, pagination, preview, HEIC/MOV support |
| Device | No communication | Device tab with info, battery, storage, apps, screenshot, controls |
| Mirroring | Non-existent | USB mirroring with parallel capture, interactive touch, iOS 17+ tunnel support |
| Backup | Browse only | Backup creation with detailed progress, ETA, speed, two backends |
| Search | Basic, buggy | Full-text across all columns, quick filters (WhatsApp, Contacts, Notes...), batch export |
| Apps | Simple listing | Expandable file tree per app with export |
| Security | Resource leaks | Path traversal protection, temp zeroing, char[] for passwords, try-with-resources |
| Tests | None | 36 unit tests (JUnit 5) |
| Tools | Requires manual install | Automatic download of Python, ffmpeg, ImageMagick on Windows |

---

## Recent changes

### PR review adjustments

- Fixed Media tab gallery resize — FlowPane now recalculates columns when the window or SplitPane divider is resized
- Translated all 40 Portuguese strings in `mirror_stream.py` to English
- Restored "Hide symlinks and empty folders" checkbox in the Files tab with functional filter

**Commits:** `4e7e5ef`

### USB mirror FPS optimization

- Moved frame processing (`optimize_frame`) from the sequential consumer into parallel capture workers — each worker now captures, resizes, and encodes independently
- Changed image resampling from `BILINEAR` to `NEAREST` (significantly faster resize)
- Reduced max output resolution from 1280px to 960px (less data per frame)
- Lowered JPEG quality from 65 to 50 (smaller frame size, faster encode)
- Increased parallel workers from 3 to 4 concurrent USB connections
- Added bounded queue (`maxsize=8`) to prevent memory buildup from stale frames

### Backup progress fix for Linux

- Fixed backup progress parsing on Linux with improved speed calculation
- Exponential smoothing for transfer speed display (0.3 instant + 0.7 previous)
- Better ETA estimation based on remaining bytes and smoothed speed
- Improved tqdm output parsing for pymobiledevice3 backend (char-by-char reading to capture `\r` lines)
- Progress milestones logged every 5%

**Commits:** `c5b7c32`

### ImageMagick portable download fix

- Fixed ImageMagick download URL — GitHub releases now only distribute `.7z` archives instead of `.zip`
- Added Apache Commons Compress and XZ dependencies for 7z extraction support
- Changed download format from ZIP to 7z with proper extraction
- Simplified Files tab by removing ImageMagick as a build-time dependency

**Commits:** `75e4c9c`, `a9d7fdb`

### Dialog theme consistency

- Applied dark/light theme to all native dialogs and alerts (password prompt, confirmations, errors)
- Dialogs now use the same stylesheet and theme class as the main window

**Commits:** `6e2047e`

### Device tab pymobiledevice3 fallback

- Fixed Device tab not recognizing connected iPhone when libimobiledevice is unavailable
- Both `initialize()` and `pollDevice()` now check for pymobiledevice3 as a fallback backend
- App listing now works via pymobiledevice3 (`InstallationProxyService`) when `ideviceinstaller` is not available
- App uninstall now works via pymobiledevice3 as a fallback

**Commits:** `d946224`

### Mirror/Screen mirroring Windows compatibility

- Fixed tunnel startup on Windows — uses PowerShell `Start-Process -Verb RunAs` instead of `pkexec` for privilege elevation
- Removed `--daemonize` flag on Windows (`os.fork()` does not exist on Windows; `Start-Process` already detaches the process)
- Fixed process termination on Windows — uses `taskkill /F /IM` instead of `pkill`
- Adapted `mirror_stream.py` for Windows: TCP socket instead of pipe file descriptors, conditional `select` import, uxplay search in common install directories
- Fixed `try_auto_mount` to find `pymobiledevice3.exe` on Windows (was looking for the Linux name without `.exe`)
- **AirPlay mirroring temporarily disabled** on Windows due to incompatibility with Windows 11 Smart App Control (blocks unsigned uxplay-windows installer). USB mirroring works normally. AirPlay remains fully functional on Linux.

**Commits:** `7e3545b`

---

## Detailed improvements

### 1. Complete interface redesign

- Professional dark theme with redesigned CSS (+1000 lines of styles)
- Selectable light/dark variants in preferences
- Main window enlarged from ~800x600 to 1200x700
- Status bar with total files, backup size and encryption state
- Fullscreen welcome screen with gradient when no backup is open
- Drag & drop: drag backup folder directly onto the sidebar
- Keyboard shortcuts: `Ctrl+O` (open), `Ctrl+F` (search), `Ctrl+Q` (quit), `F5` (reload)
- Sidebar with date sorting and context menu (open directory, close backup)
- Replaced all `System.out.println` and `printStackTrace` with SLF4J
- File size formatting with `FileSize` utility (KB/MB/GB)

**Commits:** `e4885d4`, `6553149`, `5bf4409`, `4c82032`

### 2. Media gallery

New **Media** tab with visual browsing of photos and videos from the backup:

- Thumbnail grid (90x90px) with async loading via 4-thread pool
- LRU thumbnail cache (ConcurrentHashMap) for smooth navigation
- Filters: All / Photos / Videos
- Pagination with 100 items per page
- Full-size preview panel (up to 800px) with filename, size, domain and path
- HEIC/HEIF support via ImageMagick (primary) or ffmpeg (fallback), conversion to JPEG
- Video thumbnail support (MOV/MP4/M4V/AVI) via ffmpeg (frame extraction at 0.5s)
- Individual or batch export with options: directory structure, timestamp preservation, skip existing
- Formats: JPG, PNG, HEIC, HEIF, GIF, BMP, TIFF, MOV, MP4, M4V, AVI

**Commits:** `e2873d0`, `eff5f8d`

### 3. Live device control

New **Device** tab for USB communication with iPhone via libimobiledevice or pymobiledevice3:

- Automatic detection of connected iPhone (polling every 3 seconds)
- Device information: model, iOS version, serial, UDID, Wi-Fi MAC
- Battery: level and state (charging/discharging)
- Storage: visual bar with used/total
- Installed apps: listing with filter (User/System), search and uninstall
- Screenshot capture (direct to file)
- Power controls: restart, shutdown, suspend
- Dual backend: libimobiledevice primary, pymobiledevice3 fallback for all operations

**Commits:** `c9b26c9`, `a7597ba`, `08002d1`

### 4. Screen mirroring (Mirror)

New **Mirror** tab with USB screen mirroring:

**USB (direct) — all platforms:**
- Capture via pymobiledevice3 `ScreenshotService` with up to 4 parallel USB connections
- Frame processing pipeline: capture → resize (960px max, NEAREST) → JPEG encode (quality 50) → queue → output
- Bounded frame queue (`maxsize=8`) with automatic old-frame skipping
- Java-side frame dropping via `AtomicReference` to always render latest frame
- iOS 17+ with automatic tunnel via tunneld (TCP protocol)
- iOS 16 and below with direct ScreenshotService
- Windows support with PowerShell privilege elevation for tunnel
- Fallback chain: ScreenshotService → tunneld → DVT instruments → idevicescreenshot

**AirPlay (wireless) — Linux only:**
- Streaming via AirPlay using uxplay as a server
- GStreamer pipeline: `videoconvert ! jpegenc quality=70`
- Linux: pipe file descriptors with `select()` for frame reading
- Windows: TCP socket approach (code ready but UI disabled)
- SOI/EOI parsing for JPEG frame extraction
- Temporarily disabled on Windows due to Smart App Control blocking unsigned uxplay-windows installer

**Common:**
- Interactive touch: tap and swipe forwarded to device via WDA HTTP (port 8100)
- View-only mode when WDA is not available
- 30s timeout in CONNECTING state with process monitoring
- Retry with 5 attempts and orphan process cleanup (atexit handler)
- Dark/light theme styles for toolbar and state badges

**Commits:** `56ef57f`, `9b45aa6`, `4cf793d`, `7e3545b`

### 5. Backup creation

New **Create Backup** button on the welcome screen and in the File menu:

- Detects device via libimobiledevice or pymobiledevice3
- Queries device info to estimate total size (used storage)
- Detailed progress window:
  - Progress bar with percentage
  - Transfer speed in MB/s (exponential smoothing: 0.3 instant + 0.7 previous)
  - Estimated time remaining (ETA) based on remaining bytes
  - Bytes transferred / estimated total
  - Real-time log
  - Progress milestones every 5%
- Two backend support:
  - `idevicebackup2` (libimobiledevice)
  - `pymobiledevice3 backup2` (fallback)
- tqdm progress parser for pymobiledevice3 (char-by-char reading to capture `\r` lines)
- Cancellation with confirmation and full process tree destruction
- Destination automatically registered as backup root after success

**Commits:** `b2d1d44`, `eb26be2`, `2608fd7`, `3c581e1`, `85f09c9`, `73a4690`, `aac3909`, `57048da`, `42bb9a7`, `42b90ca`, `5082ebb`, `08002d1`, `917cf09`, `b18092a`, `991618b`, `45bcda9`, `c5b7c32`

### 6. Automatic tool setup (Windows)

**Portable Python:**
- When Python is not on the system, automatically downloads Python 3.12.8 embeddable (~15 MB)
- Extracts to `~/.config/itunes-backup-explorer/python-portable/`
- Patches `._pth` file to enable pip support
- Installs pip, setuptools, wheel and pymobiledevice3
- Setup triggered automatically when clicking Create Backup or opening Mirror tab
- Progress window with detailed log

**Portable ffmpeg and ImageMagick:**
- When missing on Windows, offers automatic download (~135 MB total)
- ffmpeg: GPL build from BtbN/FFmpeg-Builds (~85 MB)
- ImageMagick: portable Q16-HDRI build in 7z format (~50 MB)
- Stored in `~/.config/itunes-backup-explorer/ffmpeg-portable/` and `imagemagick-portable/`
- Confirmation dialog when opening first backup (once per session)
- Detection order: bundled in MSI > portable in home > system PATH
- On Linux: uses system tools normally, no download needed

**Commits:** `473d040`, `8cec282`, `75e4c9c`, `a9d7fdb`, `6e2047e`

### 7. Improved file search

- Fixed NPE that prevented search from working
- Full-text search across all columns (fileID, domain, relativePath)
- Domain filter dropdown
- SQL LIKE wildcards support
- File type quick filters: Images, Videos, Databases, Plists, Text
- Quick filter buttons: Photos, Videos, WhatsApp, Contacts, Messages, Notes, Voice Memos
- Responsive columns (19%, 24%, 8%, 34%, 12%) without horizontal scrollbar
- Batch export of results with directory structure and timestamp options
- Configurable result limit in preferences

**Commits:** `884663d`

### 8. Apps browser

- **Apps** tab with listing of all apps from the backup
- Expandable directory tree per app with hierarchical file structure
- Name (extracted from bundle ID), bundle ID and version for each app
- Individual app data export with progress dialog and per-file error handling
- Locked backup guard to prevent error when accessing unlocked database

**Commits:** `4526f22`

### 9. Expanded preferences

- Theme: Dark / Light
- Auto-select newest backup
- Configurable search limits
- Timestamp preservation on extraction
- Directory structure creation on extraction
- Delete confirmation
- Backup roots management with overlap detection (parent/child)
- Standardized English text

**Commits:** `e2873d0`, `c520079`, `ff4d417`

### 10. Security and robustness

- **Path traversal protection** on file extraction
- **Try-with-resources** on all PreparedStatement/ResultSet (fixes leak)
- **Byte overflow** fixed in UID-to-index conversion in KeyBag
- **char[]** for passwords instead of String, with cleanup after use
- **Temp file zeroing** (decrypted database) with `deleteOnExit`
- **Null safety** on optional fields of BackupManifest and BackupInfo
- **Synchronized access** to database connection
- **Bounds validation** on BackupFile object access
- **Exception propagation** for SQL instead of silently swallowing
- **DateTimeFormatter** instead of SimpleDateFormat (thread-safe)
- **Locale.ROOT** in numeric formatting to avoid decimal comma
- dd-plist dependency migrated from JitPack to Maven Central

**Commits:** `8dec612`, `0f8fdd0`

### 11. Tests

36 unit tests added (JUnit 5):

- `KeyBagUidTest` — UID-to-index conversion
- `BackupFilePaddingFixerTest` — PKCS#7 padding detection/removal
- `BackupPathUtilsTest` — path manipulation
- `FileSizeTest` — human-readable size formatting

**Commits:** `0f8fdd0`

### 12. Convenience scripts

- `compile.bat` / `compile.sh` — compiles the fat JAR with a double-click
- `run.bat` / `run.sh` — compiles (if needed) and runs the app
- `scripts/run-dev.sh` — development mode execution via Maven
- Automatic JAR detection without hardcoded version

**Commits:** `28ec3c1`

---

## User instructions

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| **Java (JDK)** | 18 or higher | Required to compile and run |
| **Apache Maven** | 3.8+ | Required to compile |
| **Git** | any | To clone the repository |

> On Windows, install the JDK (e.g., [Adoptium](https://adoptium.net/)) and add it to PATH.
> Maven can be installed via [sdkman](https://sdkman.io/) or [direct download](https://maven.apache.org/download.cgi).

### Quick installation

```bash
git clone https://github.com/fredac100/iTunes-Backup-Explorer.git
cd iTunes-Backup-Explorer
```

**Windows:**
```
compile.bat
run.bat
```

**Linux/macOS:**
```bash
./compile.sh
./run.sh
```

`compile` builds the fat JAR (required on first run). `run` launches the app.

### Optional dependencies

These tools are only needed for specific features. **On Windows, the app downloads them automatically** when needed.

| Tool | Feature | Linux (apt) | Windows |
|------|---------|-------------|---------|
| libimobiledevice | Device tab, USB backup | `sudo apt install libimobiledevice-utils` | Included in Apple Devices / iTunes |
| pymobiledevice3 | Mirror tab, backup fallback, device info | `pip install pymobiledevice3` | **Automatic** (downloads portable Python 3.12.8 if needed) |
| ffmpeg | Video thumbnails | `sudo apt install ffmpeg` | **Automatic** (downloads on first need) |
| ImageMagick | HEIC thumbnails | `sudo apt install imagemagick libheif1` | **Automatic** (downloads on first need) |
| uxplay | AirPlay wireless mirroring | `sudo apt install uxplay` | Not available (temporarily disabled) |

### How to use

#### Browse backups

1. Open the app — iTunes/Finder backups are detected automatically
2. If the backup is encrypted, enter the password when prompted
3. Browse the tabs: **Info**, **Files**, **Media**, **Apps**, **Search**
4. To open a backup from another location: `Ctrl+O` or drag the folder onto the sidebar

#### Create iPhone backup

1. Connect the iPhone via USB and tap "Trust" on the device
2. Click **Create Backup** on the welcome screen
3. Choose the destination
4. Follow the progress — cancellable at any time

> On Windows, if no communication tool is installed, the app offers to install automatically.

#### Media gallery

1. Open a backup and unlock if needed
2. Go to the **Media** tab
3. Use filters (All / Photos / Videos) and pagination
4. Click a thumbnail for preview, double-click to open the file
5. Use **Export** to save individual files or in batch

> On Windows, if thumbnails don't appear for videos or HEIC, the app offers to download ffmpeg and ImageMagick automatically.

#### Screen mirroring

1. Go to the **Mirror** tab
2. Connect the iPhone via USB — the app detects it and starts the stream
3. On iOS 17+, the app will request admin privileges to start the tunnel service
4. Tap and swipe on the mirrored screen to interact (requires WDA)

> AirPlay wireless mirroring is currently available on Linux only. On Windows, it is temporarily disabled due to Smart App Control compatibility issues.

#### Device control

1. Connect the iPhone via USB
2. Go to the **Device** tab
3. View info, battery, storage and installed apps
4. Use the buttons for screenshot, restart, shutdown or suspend

> If libimobiledevice is not available, the app automatically falls back to pymobiledevice3 for device detection, info, app listing, and uninstall.

### Compiling and running

**Windows:**
```
compile.bat
run.bat
```

**Linux/macOS:**
```bash
./compile.sh
./run.sh
```

`compile` builds the fat JAR. `run` compiles (if needed) and launches the app.

<details>
<summary>Advanced Maven commands</summary>

```bash
# Fat JAR (all platforms)
mvn clean compile assembly:single

# Native installer (MSI on Windows, DEB on Linux)
mvn clean package

# Multi-platform fat JAR (Windows + Linux + ARM macOS)
mvn clean compile assembly:single -Pmost_platforms
```
</details>

### User directory structure

The app stores data in `~/.config/itunes-backup-explorer/`:

```
~/.config/itunes-backup-explorer/
    python-portable/          # Python 3.12.8 embeddable (Windows, ~50 MB)
    python-venv/              # Virtualenv with pymobiledevice3 (Linux)
    ffmpeg-portable/          # Portable ffmpeg (Windows, ~85 MB)
    imagemagick-portable/     # Portable ImageMagick (Windows, ~50 MB)
```

User preferences are stored via `java.util.prefs.Preferences` (Windows registry or `~/.java` on Linux).

---

## Architecture

```
me.maxih.itunes_backup_explorer/
    api/            Domain and backup logic (ITunesBackup, KeyBag, BackupFile)
    ui/             JavaFX controllers (Window, Files, Media, Apps, Device, Mirror, Search, Preferences)
    util/           Utilities (DeviceService, MirrorService, MediaConverter, ThumbnailService, FileSize)
```

### External runtime dependencies

| Component | Technology |
|-----------|-----------|
| Language | Java 18+ |
| GUI | JavaFX 23 (FXML + controllers) |
| Build | Apache Maven |
| Encryption | Bouncy Castle 1.80 (AES-256, PBKDF2, AES-Wrap) |
| Database | SQLite (Xerial sqlite-jdbc 3.49) |
| Plist | dd-plist |
| Compression | Apache Commons Compress + XZ (7z extraction) |
| Mirroring | pymobiledevice3 (Python) |
| Device | libimobiledevice (CLI) + pymobiledevice3 (fallback) |
| Video thumbnails | ffmpeg |
| HEIC thumbnails | ImageMagick (primary) / ffmpeg (fallback) |
| Tests | JUnit Jupiter 5 |

---

## Project origin

This project is a fork of the original [iTunes Backup Explorer](https://github.com/MaxiHuHe04/iTunes-Backup-Explorer) by [MaxiHuHe04](https://github.com/MaxiHuHe04). The original project provided the foundation for navigating encrypted iTunes backups with AES-256 and Apple KeyBag support. This fork significantly extends the project with screen mirroring, media gallery, live device control, backup creation, automatic tool setup, and a completely redesigned modern interface.
