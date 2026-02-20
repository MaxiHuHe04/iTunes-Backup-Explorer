#!/usr/bin/env python3

import sys
import struct
import time
import os
import tempfile
import subprocess
import logging
import asyncio
import threading
import queue as _queue
import signal
import atexit
import socket as _socket

logging.disable(logging.CRITICAL)

IS_WINDOWS = sys.platform == 'win32'

if not IS_WINDOWS:
    import select

_active_uxplay: subprocess.Popen | None = None


def _cleanup_uxplay():
    global _active_uxplay
    proc = _active_uxplay
    if proc is not None and proc.poll() is None:
        proc.kill()
        try:
            proc.wait(timeout=3)
        except Exception:
            pass
    _active_uxplay = None


atexit.register(_cleanup_uxplay)
if not IS_WINDOWS:
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

try:
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.services.screenshot import ScreenshotService
    USE_PYMOBILEDEVICE3 = True
except ImportError:
    USE_PYMOBILEDEVICE3 = False

try:
    from PIL import Image as PILImage
    import io as _io
    HAS_PILLOW = True
except ImportError:
    HAS_PILLOW = False

JPEG_QUALITY = 50
MAX_LONG_SIDE = 960


def optimize_frame(raw_data: bytes) -> tuple[bytes, int, int]:
    if not HAS_PILLOW:
        w = h = 0
        if len(raw_data) >= 24 and raw_data[:4] == b'\x89PNG':
            w = struct.unpack(">I", raw_data[16:20])[0]
            h = struct.unpack(">I", raw_data[20:24])[0]
        return raw_data, w, h

    img = PILImage.open(_io.BytesIO(raw_data))
    orig_w, orig_h = img.size

    long_side = max(orig_w, orig_h)
    if long_side > MAX_LONG_SIDE:
        scale = MAX_LONG_SIDE / long_side
        img = img.resize((int(orig_w * scale), int(orig_h * scale)), PILImage.NEAREST)

    if img.mode != 'RGB':
        img = img.convert('RGB')

    buf = _io.BytesIO()
    img.save(buf, format='JPEG', quality=JPEG_QUALITY)
    return buf.getvalue(), orig_w, orig_h


def write_frame(image_data: bytes, orig_width: int, orig_height: int) -> None:
    total = 8 + len(image_data)
    sys.stdout.buffer.write(struct.pack(">I", total))
    sys.stdout.buffer.write(struct.pack(">II", orig_width, orig_height))
    sys.stdout.buffer.write(image_data)
    sys.stdout.buffer.flush()


def convert_tiff_to_png(tiff_data: bytes) -> bytes | None:
    try:
        from PIL import Image
        import io
        img = Image.open(io.BytesIO(tiff_data))
        output = io.BytesIO()
        img.save(output, format="PNG")
        return output.getvalue()
    except ImportError:
        pass

    tiff_path = None
    png_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".tiff", delete=False) as tiff_file:
            tiff_file.write(tiff_data)
            tiff_path = tiff_file.name

        png_path = tiff_path.replace(".tiff", ".png")
        result = subprocess.run(
            ["convert", tiff_path, png_path],
            capture_output=True,
            timeout=10,
        )
        if result.returncode == 0 and os.path.exists(png_path):
            with open(png_path, "rb") as f:
                return f.read()
    except Exception:
        pass
    finally:
        for p in [tiff_path, png_path]:
            if p:
                try:
                    os.unlink(p)
                except Exception:
                    pass

    return None


def parse_jpeg_dimensions(data: bytes) -> tuple[int, int]:
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            return 0, 0
        marker = data[i + 1]
        if marker in (0xC0, 0xC2):
            h = struct.unpack(">H", data[i + 5:i + 7])[0]
            w = struct.unpack(">H", data[i + 7:i + 9])[0]
            return w, h
        elif marker == 0xDA:
            break
        elif 0xD0 <= marker <= 0xD9:
            i += 2
        elif marker in (0x00, 0x01):
            i += 2
        else:
            if i + 3 < len(data):
                seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
                i += 2 + seg_len
            else:
                break
    return 0, 0


def _find_uxplay() -> str:
    """Find the uxplay executable, checking install directories on Windows."""
    if not IS_WINDOWS:
        return "uxplay"
    import shutil
    if shutil.which("uxplay"):
        return "uxplay"
    candidates = []
    for env_var in ("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"):
        base = os.environ.get(env_var, "")
        if base:
            candidates.append(os.path.join(base, "uxplay-windows", "uxplay.exe"))
            candidates.append(os.path.join(base, "Programs", "uxplay-windows", "uxplay.exe"))
    for path in candidates:
        if os.path.isfile(path):
            return path
    return "uxplay"


def _kill_uxplay() -> None:
    if IS_WINDOWS:
        subprocess.run(["taskkill", "/F", "/IM", "uxplay.exe"],
                       capture_output=True)
    else:
        subprocess.run(["pkill", "-9", "-f", "uxplay"], capture_output=True)
    time.sleep(0.5)


def _wait_uxplay_listening(port: int = 7000, timeout: float = 10.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with _socket.create_connection(('127.0.0.1', port), timeout=0.5) as s:
                return True
        except (ConnectionRefusedError, OSError, TimeoutError):
            time.sleep(0.3)
    return False


def _launch_uxplay_pipe(w_fd: int) -> subprocess.Popen:
    """Launch uxplay using pipe fd (Linux/macOS)."""
    global _active_uxplay
    uxplay = _find_uxplay()
    vc = "videoconvert ! jpegenc quality=70"
    vs = f"fdsink fd={w_fd} sync=false"
    proc = subprocess.Popen(
        [uxplay, "-nh", "-n", "Mirror", "-p", "7000",
         "-vc", vc, "-vs", vs, "-as", "0"],
        stderr=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        pass_fds=(w_fd,),
    )
    _active_uxplay = proc
    return proc


def _launch_uxplay_tcp(tcp_port: int) -> subprocess.Popen:
    """Launch uxplay using TCP socket (Windows)."""
    global _active_uxplay
    uxplay = _find_uxplay()
    vc = "videoconvert ! jpegenc quality=70"
    vs = f"tcpclientsink host=127.0.0.1 port={tcp_port}"
    proc = subprocess.Popen(
        [uxplay, "-nh", "-n", "Mirror", "-p", "7000",
         "-vc", vc, "-vs", vs, "-as", "0"],
        stderr=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
    )
    _active_uxplay = proc
    return proc


def _read_airplay_frames_fd(proc: subprocess.Popen, r_fd: int,
                            connect_timeout: float = 10.0) -> int:
    """Read frames from a pipe file descriptor (Linux/macOS)."""
    uxplay_error = []

    def monitor_stderr():
        try:
            for raw_line in proc.stderr:
                text = raw_line.decode('utf-8', errors='replace').strip()
                if text:
                    print(f"INFO: uxplay: {text}", file=sys.stderr, flush=True)
                    if "ERROR" in text or "error" in text:
                        uxplay_error.append(text)
        except Exception:
            pass

    stderr_thread = threading.Thread(target=monitor_stderr, daemon=True)
    stderr_thread.start()

    frame_count = 0
    cached_w, cached_h = 0, 0
    buf = b''

    try:
        while True:
            if proc.poll() is not None and not buf:
                break

            timeout = connect_timeout if frame_count == 0 else 5.0
            ready, _, _ = select.select([r_fd], [], [], timeout)
            if not ready:
                if frame_count == 0:
                    print(f"INFO: Timeout of {connect_timeout}s waiting for AirPlay connection",
                          file=sys.stderr, flush=True)
                break

            try:
                chunk = os.read(r_fd, 131072)
            except OSError:
                break
            if not chunk:
                break

            buf += chunk

            while True:
                soi = buf.find(b'\xff\xd8')
                if soi == -1:
                    buf = b''
                    break
                if soi > 0:
                    buf = buf[soi:]

                eoi = buf.find(b'\xff\xd9', 2)
                if eoi == -1:
                    break

                frame = buf[:eoi + 2]
                buf = buf[eoi + 2:]

                if cached_w == 0:
                    cached_w, cached_h = parse_jpeg_dimensions(frame)

                write_frame(frame, cached_w, cached_h)
                frame_count += 1
    except (BrokenPipeError, KeyboardInterrupt):
        pass

    if frame_count == 0 and uxplay_error:
        print(f"INFO: uxplay reported errors: {'; '.join(uxplay_error)}", file=sys.stderr, flush=True)

    return frame_count


def _read_airplay_frames_socket(proc: subprocess.Popen, conn: _socket.socket,
                                connect_timeout: float = 10.0) -> int:
    """Read frames from a TCP socket (Windows)."""
    uxplay_error = []

    def monitor_stderr():
        try:
            for raw_line in proc.stderr:
                text = raw_line.decode('utf-8', errors='replace').strip()
                if text:
                    print(f"INFO: uxplay: {text}", file=sys.stderr, flush=True)
                    if "ERROR" in text or "error" in text:
                        uxplay_error.append(text)
        except Exception:
            pass

    stderr_thread = threading.Thread(target=monitor_stderr, daemon=True)
    stderr_thread.start()

    frame_count = 0
    cached_w, cached_h = 0, 0
    buf = b''

    try:
        while True:
            if proc.poll() is not None and not buf:
                break

            timeout = connect_timeout if frame_count == 0 else 5.0
            conn.settimeout(timeout)
            try:
                chunk = conn.recv(131072)
            except _socket.timeout:
                if frame_count == 0:
                    print(f"INFO: Timeout of {connect_timeout}s waiting for AirPlay connection",
                          file=sys.stderr, flush=True)
                break
            except OSError:
                break
            if not chunk:
                break

            buf += chunk

            while True:
                soi = buf.find(b'\xff\xd8')
                if soi == -1:
                    buf = b''
                    break
                if soi > 0:
                    buf = buf[soi:]

                eoi = buf.find(b'\xff\xd9', 2)
                if eoi == -1:
                    break

                frame = buf[:eoi + 2]
                buf = buf[eoi + 2:]

                if cached_w == 0:
                    cached_w, cached_h = parse_jpeg_dimensions(frame)

                write_frame(frame, cached_w, cached_h)
                frame_count += 1
    except (BrokenPipeError, KeyboardInterrupt):
        pass

    if frame_count == 0 and uxplay_error:
        print(f"INFO: uxplay reported errors: {'; '.join(uxplay_error)}", file=sys.stderr, flush=True)

    return frame_count


def stream_airplay() -> None:
    MAX_RETRIES = 5
    RETRY_DELAY = 1

    uxplay_not_found_msg = (
        "MIRROR_ERROR: uxplay not found. "
        + ("Install uxplay-windows: https://github.com/leapbtw/uxplay-windows"
           if IS_WINDOWS else "Install with: sudo apt install uxplay")
    )

    for attempt in range(1, MAX_RETRIES + 1):
        print(f"INFO: Starting AirPlay server via uxplay (attempt {attempt}/{MAX_RETRIES})...",
              file=sys.stderr, flush=True)

        _kill_uxplay()

        # Setup communication channel
        r_fd = None
        w_fd = None
        srv_sock = None
        conn_sock = None

        if IS_WINDOWS:
            srv_sock = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
            srv_sock.bind(('127.0.0.1', 0))
            tcp_port = srv_sock.getsockname()[1]
            srv_sock.listen(1)
        else:
            r_fd, w_fd = os.pipe()

        try:
            if IS_WINDOWS:
                proc = _launch_uxplay_tcp(tcp_port)
            else:
                proc = _launch_uxplay_pipe(w_fd)
        except FileNotFoundError:
            print(uxplay_not_found_msg, file=sys.stderr, flush=True)
            if IS_WINDOWS:
                srv_sock.close()
            else:
                os.close(r_fd)
                os.close(w_fd)
            sys.exit(1)

        if not IS_WINDOWS:
            os.close(w_fd)

        time.sleep(1.0)
        if proc.poll() is not None:
            err = ""
            try:
                err = proc.stderr.read().decode('utf-8', errors='replace').strip()
            except Exception:
                pass
            if IS_WINDOWS:
                srv_sock.close()
            else:
                os.close(r_fd)

            if "DNS-SD" in err or "DNSService" in err:
                if IS_WINDOWS:
                    print("MIRROR_ERROR: Bonjour service is not running. "
                          "Install iTunes or Bonjour Print Services from Apple.",
                          file=sys.stderr, flush=True)
                else:
                    print("MIRROR_ERROR: DNS-SD service (Avahi) is not running. "
                          "Run: sudo systemctl start avahi-daemon",
                          file=sys.stderr, flush=True)
                sys.exit(1)

            if attempt < MAX_RETRIES:
                print(f"INFO: uxplay terminated prematurely, retrying in {RETRY_DELAY}s...",
                      file=sys.stderr, flush=True)
                time.sleep(RETRY_DELAY)
                continue

            print(f"MIRROR_ERROR: uxplay failed after {MAX_RETRIES} attempts: {err}",
                  file=sys.stderr, flush=True)
            sys.exit(1)

        if not _wait_uxplay_listening(7000, timeout=8.0):
            print("INFO: uxplay did not open port 7000 in time",
                  file=sys.stderr, flush=True)
            _cleanup_uxplay()
            if IS_WINDOWS:
                srv_sock.close()
            else:
                os.close(r_fd)
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY)
                continue
            print("MIRROR_ERROR: uxplay failed to start server on port 7000",
                  file=sys.stderr, flush=True)
            sys.exit(1)

        # On Windows, accept the TCP connection from uxplay
        if IS_WINDOWS:
            try:
                srv_sock.settimeout(15.0)
                conn_sock, _ = srv_sock.accept()
                srv_sock.close()
                srv_sock = None
            except _socket.timeout:
                print("INFO: uxplay did not connect to TCP socket in time",
                      file=sys.stderr, flush=True)
                _cleanup_uxplay()
                if srv_sock:
                    srv_sock.close()
                if attempt < MAX_RETRIES:
                    time.sleep(RETRY_DELAY)
                    continue
                print("MIRROR_ERROR: uxplay failed to connect to video socket",
                      file=sys.stderr, flush=True)
                sys.exit(1)

        time.sleep(1.5)
        print("MIRROR_AIRPLAY_READY", file=sys.stderr, flush=True)

        try:
            if IS_WINDOWS:
                frame_count = _read_airplay_frames_socket(proc, conn_sock)
            else:
                frame_count = _read_airplay_frames_fd(proc, r_fd)
        except Exception as e:
            print(f"INFO: Exception in _read_airplay_frames: {e}", file=sys.stderr, flush=True)
            frame_count = 0

        if IS_WINDOWS:
            if conn_sock:
                try:
                    conn_sock.close()
                except OSError:
                    pass
        else:
            try:
                os.close(r_fd)
            except OSError:
                pass
        _cleanup_uxplay()

        if frame_count > 0:
            sys.exit(0)

        if attempt < MAX_RETRIES:
            print(f"INFO: No frames received, restarting uxplay in {RETRY_DELAY}s...",
                  file=sys.stderr, flush=True)
            time.sleep(RETRY_DELAY)
            continue

    print("MIRROR_ERROR: AirPlay ended without sending video after multiple attempts. "
          "Make sure the iPhone is on the same network and try again.",
          file=sys.stderr, flush=True)
    sys.exit(1)


def _start_capture_worker(fq, capture_fn, label="primary"):
    def worker():
        while True:
            try:
                raw = capture_fn()
                data, w, h = optimize_frame(raw)
                fq.put((data, w, h))
            except (ConnectionError, BrokenPipeError, OSError) as e:
                print(f"MIRROR_ERROR: Device disconnected ({label}): {e}", file=sys.stderr, flush=True)
                fq.put(None)
                return
            except KeyboardInterrupt:
                fq.put(None)
                return
            except Exception as e:
                print(f"MIRROR_ERROR: ({label}) {e}", file=sys.stderr, flush=True)
                time.sleep(1)

    t = threading.Thread(target=worker, daemon=True)
    t.start()
    return t


def _consume_frames(fq) -> None:
    try:
        while True:
            item = fq.get()
            if item is None:
                sys.exit(1)
            while not fq.empty():
                try:
                    latest = fq.get_nowait()
                    if latest is None:
                        sys.exit(1)
                    item = latest
                except _queue.Empty:
                    break
            data, w, h = item
            write_frame(data, w, h)
    except KeyboardInterrupt:
        sys.exit(0)


def screenshot_loop(screenshot_service, udid=None) -> None:
    fq = _queue.Queue(maxsize=8)
    _start_capture_worker(fq, screenshot_service.take_screenshot, "worker-1")

    if udid and USE_PYMOBILEDEVICE3:
        for i in range(3):
            try:
                lockdown = create_using_usbmux(serial=udid)
                ss = ScreenshotService(lockdown=lockdown)
                ss.take_screenshot()
                _start_capture_worker(fq, ss.take_screenshot, f"worker-{i+2}")
                print(f"INFO: Parallel capture worker-{i+2} active", file=sys.stderr, flush=True)
            except Exception as e:
                print(f"INFO: Parallel capture worker-{i+2} not available: {e}", file=sys.stderr, flush=True)
                break

    _consume_frames(fq)


def dvt_screenshot_loop(dvt) -> None:
    from pymobiledevice3.services.dvt.instruments.screenshot import Screenshot
    ss = Screenshot(dvt)
    print("INFO: DVT Screenshot connected", file=sys.stderr, flush=True)
    fq = _queue.Queue()
    _start_capture_worker(fq, ss.get_screenshot)
    _consume_frames(fq)


def try_screenshot_service(lockdown, udid=None) -> bool:
    try:
        screenshot_service = ScreenshotService(lockdown=lockdown)
        print("INFO: ScreenshotService connected", file=sys.stderr, flush=True)
        screenshot_loop(screenshot_service, udid)
        return True
    except Exception:
        return False


def try_auto_mount(udid: str) -> bool:
    venv_bin = os.path.dirname(sys.executable)
    exe_name = "pymobiledevice3.exe" if IS_WINDOWS else "pymobiledevice3"
    pmd3 = os.path.join(venv_bin, exe_name)

    if not os.path.isfile(pmd3):
        return False

    print("INFO: Attempting auto-mount of developer image...", file=sys.stderr, flush=True)
    try:
        result = subprocess.run(
            [pmd3, "mounter", "auto-mount", "--udid", udid],
            capture_output=True,
            timeout=30,
        )
        if result.returncode == 0:
            print("INFO: Auto-mount completed", file=sys.stderr, flush=True)
            return True
        print(f"INFO: Auto-mount returned code {result.returncode}", file=sys.stderr, flush=True)
        return False
    except Exception as e:
        print(f"INFO: Auto-mount failed: {e}", file=sys.stderr, flush=True)
        return False


def get_tunnel_info(udid: str):
    try:
        import urllib.request
        import json
        req = urllib.request.urlopen("http://127.0.0.1:49151/", timeout=5)
        data = json.loads(req.read())
    except Exception:
        return None

    if not data or not isinstance(data, dict):
        return None

    for tunnel_udid, tunnels in data.items():
        if tunnel_udid == udid or udid in tunnel_udid or tunnel_udid.replace("-", "") == udid:
            if isinstance(tunnels, list) and tunnels:
                return tunnels[0]

    all_tunnels = [t for tunnels in data.values() if isinstance(tunnels, list) for t in tunnels]
    if all_tunnels:
        return all_tunnels[0]

    return None


def try_tunneld_screenshot(udid: str) -> bool:
    try:
        from pymobiledevice3.remote.remote_service_discovery import RemoteServiceDiscoveryService
    except ImportError:
        return False

    target = get_tunnel_info(udid)
    if target is None:
        return False

    host = target.get("tunnel-address", target.get("address", ""))
    port = target.get("tunnel-port", target.get("port", 0))

    if not host or not port:
        return False

    print(f"INFO: Connecting via tunneld {host}:{port}", file=sys.stderr, flush=True)

    try:
        rsd = RemoteServiceDiscoveryService((host, port))
        asyncio.run(rsd.connect())
        print(f"INFO: RSD connected", file=sys.stderr, flush=True)
    except Exception as e:
        print(f"INFO: Failed to connect RSD: {e}", file=sys.stderr, flush=True)
        return False

    try:
        ss = ScreenshotService(rsd)
        print("INFO: ScreenshotService via tunnel connected", file=sys.stderr, flush=True)
        screenshot_loop(ss)
        return True
    except Exception as e:
        print(f"INFO: ScreenshotService unavailable ({e}), using DVT...", file=sys.stderr, flush=True)

    try:
        from pymobiledevice3.services.dvt.dvt_secure_socket_proxy import DvtSecureSocketProxyService
        dvt = DvtSecureSocketProxyService(rsd)
        dvt.__enter__()
        dvt_screenshot_loop(dvt)
        return True
    except Exception as e:
        print(f"INFO: DVT screenshot via tunnel failed: {e}", file=sys.stderr, flush=True)
        return False


def stream_idevicescreenshot(udid: str) -> None:
    print("INFO: Trying idevicescreenshot as fallback...", file=sys.stderr, flush=True)

    tmp_fd, tmp_path = tempfile.mkstemp(suffix=".png")
    os.close(tmp_fd)

    try:
        result = subprocess.run(
            ["idevicescreenshot", "-u", udid, tmp_path],
            capture_output=True,
            timeout=10,
        )
        if result.returncode != 0:
            stderr_text = result.stderr.decode("utf-8", errors="replace")
            if "Developer" in stderr_text or "screenshotr" in stderr_text:
                print("MIRROR_ERROR: TUNNEL_REQUIRED", file=sys.stderr, flush=True)
                sys.exit(1)
    except Exception:
        pass
    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass

    print("MIRROR_ERROR: TUNNEL_REQUIRED", file=sys.stderr, flush=True)
    sys.exit(1)


def stream_pymobiledevice3(udid: str) -> None:
    print("INFO: Using pymobiledevice3", file=sys.stderr, flush=True)

    try:
        lockdown = create_using_usbmux(serial=udid)
    except Exception as e:
        print(f"MIRROR_ERROR: Failed to connect: {e}", file=sys.stderr, flush=True)
        sys.exit(1)

    ios_version = 0
    try:
        ios_version = int(lockdown.product_version.split(".")[0])
    except Exception:
        pass

    if try_screenshot_service(lockdown, udid):
        return

    print("INFO: Direct ScreenshotService failed, trying alternatives...", file=sys.stderr, flush=True)

    if ios_version >= 17:
        if try_tunneld_screenshot(udid):
            return

        try_auto_mount(udid)

        try:
            lockdown2 = create_using_usbmux(serial=udid)
            if try_screenshot_service(lockdown2, udid):
                return
        except Exception:
            pass

    stream_idevicescreenshot(udid)


def main() -> None:
    if "--airplay" in sys.argv:
        stream_airplay()
        return

    if len(sys.argv) < 2:
        print("Usage: mirror_stream.py <UDID> | --airplay", file=sys.stderr, flush=True)
        sys.exit(1)

    udid = sys.argv[1]
    print(f"INFO: Starting stream for device {udid}", file=sys.stderr, flush=True)

    if USE_PYMOBILEDEVICE3:
        stream_pymobiledevice3(udid)
    else:
        stream_idevicescreenshot(udid)


if __name__ == "__main__":
    main()
