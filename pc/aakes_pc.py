# -*- coding: utf-8 -*-
"""
Jifco Cast للحاسب — مرسل ويندوز يكلّم مستقبل «عاكس» (أندرويد) بنفس البروتوكول.
يلتقط الشاشة، يشفّرها إتش ٢٦٤، يرسل الصوت الخارج من السماعة، ويعرض نافذة تحكم بسيطة.
"""
VERSION = "0.4.2"
APP_NAME = "Jifco Cast"

import json
import os
import socket
import struct
import threading
import time
import platform
import queue
from fractions import Fraction

import tkinter as tk
from tkinter import ttk, messagebox

# ---------------- البروتوكول (مطابق لـ Protocol.kt) ----------------
MAGIC = 0x53435231
SERVICE_TYPE = "_screencaster._tcp.local."
DEFAULT_PORT = 47337
MSG_HELLO, MSG_HELLO_ACK, MSG_CONFIG, MSG_FRAME, MSG_PING, MSG_PONG, MSG_BYE, MSG_RESIZE, MSG_MODE = 1, 2, 3, 4, 5, 6, 7, 8, 9
MSG_AUDIO_CONFIG, MSG_AUDIO = 10, 11
MSG_AUTH_FAIL = 12
MSG_AUDIO_DELAY = 13
DEFAULT_PIN = "1234"
SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings.json")


def load_settings():
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(d):
    try:
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
    except Exception:
        pass
FLAG_KEYFRAME = 1
HEADER = struct.Struct(">iBiqi")  # magic, type, flags, pts, len

FPS = 30
IFRAME_SEC = 2
# خيارات الدقة: (الاسم، الضلع الأطول بالبكسل، معدل البت) — 0 = دقة الشاشة الأصلية
QUALITIES = [
    ("720p — ٥ ميجابت (الافتراضي)", 1280, 5_000_000),
    ("900p — ٨ ميجابت", 1600, 8_000_000),
    ("1080p — ١٠ ميجابت", 1920, 10_000_000),
    ("1080p — ١٥ ميجابت (أوضح، أثقل)", 1920, 15_000_000),
    ("الأصلية — ٢٠ ميجابت", 0, 20_000_000),
]


class Conn:
    """اتصال واحد بالمستقبل مع قفل للكتابة (الفيديو والصوت من خيطين)."""
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.lock = threading.Lock()
        self.last_sent = time.time()

    def send(self, mtype, payload=b"", flags=0, pts=0):
        data = HEADER.pack(MAGIC, mtype, flags, pts, len(payload)) + payload
        with self.lock:
            self.sock.sendall(data)
            self.last_sent = time.time()

    def recv(self):
        hdr = self._read(HEADER.size)
        magic, mtype, flags, pts, ln = HEADER.unpack(hdr)
        if magic != MAGIC:
            raise IOError("bad magic")
        return mtype, self._read(ln), flags, pts

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise IOError("closed")
            buf += chunk
        return buf

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


# ---------------- البحث عن المستقبلات ----------------
class Discovery:
    def __init__(self, on_found):
        self.on_found = on_found
        self.zc = None
        self.browser = None

    def start(self):
        from zeroconf import Zeroconf, ServiceBrowser
        self.zc = Zeroconf()
        self.browser = ServiceBrowser(self.zc, SERVICE_TYPE, handlers=[self._handler])

    def _handler(self, zeroconf, service_type, name, state_change):
        from zeroconf import ServiceStateChange
        if state_change in (ServiceStateChange.Added, ServiceStateChange.Updated):
            info = zeroconf.get_service_info(service_type, name, timeout=2000)
            if info:
                addrs = info.parsed_addresses()
                addrs = [a for a in addrs if ":" not in a] or addrs
                if addrs:
                    props = {k.decode() if isinstance(k, bytes) else k: (v.decode() if isinstance(v, bytes) else v)
                             for k, v in (info.properties or {}).items()}
                    fn = props.get("fn") or name.replace("." + SERVICE_TYPE, "")
                    self.on_found(fn, addrs[0], info.port)

    def stop(self):
        try:
            if self.zc:
                self.zc.close()
        except Exception:
            pass
        self.zc = None


# ---------------- الالتقاط والتشفير ----------------
def split_nals(data: bytes):
    """يقسّم تيار أنكس-بي إلى وحدات نال (بدون رموز البداية)."""
    out, i, n = [], 0, len(data)
    starts = []
    while i < n - 3:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                starts.append((i, 3)); i += 3; continue
            if data[i + 2] == 0 and i + 3 < n and data[i + 3] == 1:
                starts.append((i, 4)); i += 4; continue
        i += 1
    for k, (pos, sl) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else n
        out.append(data[pos + sl:end])
    return out


# ---------------- رسم مؤشر الماوس ----------------
_ARROW = [
    "X...........", "XX..........", "XWX.........", "XWWX........", "XWWWX.......", "XWWWWX......",
    "XWWWWWX.....", "XWWWWWWX....", "XWWWWWWWX...", "XWWWWWWWWX..", "XWWWWWWWWWX.", "XWWWWWWXXXXX",
    "XWWWXWWX....", "XWWX.XWWX...", "XWX..XWWX...", "XX....XWWX..", "X.....XWWX..", ".......XX...",
]

def _arrow_bitmaps(scale):
    import numpy as np
    h, w = len(_ARROW), len(_ARROW[0])
    black = np.zeros((h, w), dtype=bool); white = np.zeros((h, w), dtype=bool)
    for y, row in enumerate(_ARROW):
        for x, c in enumerate(row):
            if c == "X": black[y, x] = True
            elif c == "W": white[y, x] = True
    if scale > 1:
        black = np.kron(black, np.ones((scale, scale), dtype=bool))
        white = np.kron(white, np.ones((scale, scale), dtype=bool))
    return black, white


def cursor_pos():
    """موضع الماوس على الشاشة الافتراضية (ويندوز)."""
    try:
        import ctypes
        from ctypes import wintypes
        pt = wintypes.POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt))
        return pt.x, pt.y
    except Exception:
        return None


def draw_cursor(arr, mon, bitmaps):
    """يرسم سهم المؤشر على إطار الالتقاط (بي جي آر إيه) إن كان الماوس داخل هذه الشاشة."""
    pos = cursor_pos()
    if not pos:
        return
    x, y = pos[0] - mon["left"], pos[1] - mon["top"]
    black, white = bitmaps
    h, w = black.shape
    H, W = arr.shape[0], arr.shape[1]
    if x < 0 or y < 0 or x >= W or y >= H:
        return
    h2, w2 = min(h, H - y), min(w, W - x)
    region = arr[y:y + h2, x:x + w2, :3]
    region[black[:h2, :w2]] = (0, 0, 0)
    region[white[:h2, :w2]] = (255, 255, 255)


class Sender(threading.Thread):
    def __init__(self, host, port, monitor_index, ui, long_side, bitrate, show_cursor=True, pin=DEFAULT_PIN):
        super().__init__(daemon=True)
        self.host, self.port, self.monitor_index, self.ui = host, port, monitor_index, ui
        self.long_side, self.bitrate = long_side, bitrate
        self.show_cursor = show_cursor
        self.pin = pin
        self.auth_failed = False
        self.running = True
        self.conn = None
        self.mode_queue = queue.Queue()
        self.frames = 0
        self.bytes_window = 0

    def set_mode(self, mode):
        self.mode_queue.put(("mode", mode))

    def set_audio_delay(self, ms):
        self.mode_queue.put(("delay", str(int(ms))))

    def stop(self):
        self.running = False

    def run(self):
        import av
        import mss
        import numpy as np
        audio_thread = None
        try:
            with mss.mss() as sct:
                mon = sct.monitors[self.monitor_index]
                sw, sh = mon["width"], mon["height"]
                ls = self.long_side or max(sw, sh)
                scale = ls / max(sw, sh) if max(sw, sh) > ls else 1.0
                w = max(160, int(sw * scale) // 16 * 16)
                h = max(160, int(sh * scale) // 16 * 16)

                self.ui.status(f"جارٍ الاتصال بـ {self.host}…")
                self.conn = Conn(self.host, self.port)
                hello = {"name": f"{APP_NAME} PC — {platform.node()}", "model": "Windows", "role": "sender",
                         "version": VERSION, "width": w, "height": h, "pin": self.pin}
                self.conn.send(MSG_HELLO, json.dumps(hello, ensure_ascii=False).encode("utf-8"))
                mtype, payload, _, _ = self.conn.recv()
                if mtype == MSG_AUTH_FAIL:
                    self.auth_failed = True
                    raise IOError("كلمة السر غير صحيحة — صحّحها في خانة «كلمة السر» وأعد المحاولة")
                if mtype != MSG_HELLO_ACK:
                    raise IOError("ردّ غير متوقع من المستقبل")
                self.ui.pin_ok(self.host, self.pin)
                peer = json.loads(payload.decode("utf-8"))
                self.conn.sock.settimeout(None)
                self.ui.status(f"يبثّ إلى {peer.get('name', self.host)} — {w}×{h} — {self.bitrate//1_000_000} ميجابت/ث")
                self.ui.streaming(True)

                # المشفّر: أقل تأخير، بلا إطارات بي، مفتاح كل ثانيتين، ترويسات مكررة
                codec = av.CodecContext.create("libx264", "w")
                codec.width, codec.height = w, h
                codec.pix_fmt = "yuv420p"
                codec.time_base = Fraction(1, FPS)
                codec.framerate = Fraction(FPS, 1)
                codec.bit_rate = self.bitrate
                codec.options = {
                    "preset": "ultrafast", "tune": "zerolatency", "profile": "baseline",
                    "x264-params": f"keyint={FPS*IFRAME_SEC}:min-keyint={FPS*IFRAME_SEC}:repeat-headers=1:annexb=1:bframes=0:rc-lookahead=0:sync-lookahead=0",
                }
                codec.open()

                audio_thread = AudioSender(self.conn, self.ui)
                audio_thread.start()

                cursor_bitmaps = _arrow_bitmaps(2 if max(sw, sh) > 1600 else 1)
                config_sent = False
                pts = 0
                frame_interval = 1.0 / FPS
                last_stat = time.time()
                last_frame = None
                last_change = time.time()
                while self.running:
                    t0 = time.time()
                    # وضع العرض
                    try:
                        while True:
                            kind, val = self.mode_queue.get_nowait()
                            self.conn.send(MSG_MODE if kind == "mode" else MSG_AUDIO_DELAY, val.encode("utf-8"))
                    except queue.Empty:
                        pass

                    img = sct.grab(mon)
                    arr = np.frombuffer(img.bgra, dtype=np.uint8).reshape(img.height, img.width, 4).copy()
                    if self.show_cursor:
                        draw_cursor(arr, mon, cursor_bitmaps)
                    # الشاشة الساكنة: لا نشفّر كل إطار، بل واحداً كل ٠.١ ث (يوفّر المعالج ويبقي الاتصال حياً)
                    changed = last_frame is None or not np.array_equal(arr, last_frame)
                    if changed:
                        last_frame = arr.copy()
                        last_change = t0
                    elif t0 - last_change < 0.1 and self.frames > 0:
                        time.sleep(max(0, frame_interval - (time.time() - t0)))
                        continue
                    if not changed:
                        last_change = t0

                    frame = av.VideoFrame.from_ndarray(arr, format="bgra")
                    frame = frame.reformat(width=w, height=h, format="yuv420p")
                    frame.pts = pts
                    pts += 1
                    for pkt in codec.encode(frame):
                        data = bytes(pkt)
                        if not config_sent:
                            sps = pps = None
                            for nal in split_nals(data):
                                t = nal[0] & 0x1F
                                if t == 7: sps = nal
                                elif t == 8: pps = nal
                            if sps and pps:
                                self.conn.send(MSG_CONFIG, b"\x00\x00\x00\x01" + sps + b"\x00\x00\x00\x01" + pps)
                                config_sent = True
                        flags = FLAG_KEYFRAME if pkt.is_keyframe else 0
                        self.conn.send(MSG_FRAME, data, flags, int(pts * 1_000_000 / FPS))
                        self.frames += 1
                        self.bytes_window += len(data)

                    now = time.time()
                    if now - self.conn.last_sent >= 3:
                        self.conn.send(MSG_PING)
                    if now - last_stat >= 1:
                        kbps = int(self.bytes_window * 8 / (now - last_stat) / 1000)
                        self.bytes_window = 0
                        last_stat = now
                        self.ui.stats(self.frames, kbps)
                    time.sleep(max(0, frame_interval - (time.time() - t0)))
                try:
                    self.conn.send(MSG_BYE)
                except Exception:
                    pass
        except Exception as e:
            self.ui.status(f"انقطع البث: {e}")
        finally:
            self.running = False
            if audio_thread:
                audio_thread.stop()
            if self.conn:
                self.conn.close()
            self.ui.streaming(False)


class AudioSender(threading.Thread):
    """يلتقط ما يخرج من سماعة الحاسب (لوب باك واسابي) ويرسله خاماً كقطع ٢٠ مللي ثانية."""
    def __init__(self, conn, ui):
        super().__init__(daemon=True)
        self.conn, self.ui = conn, ui
        self.running = True

    def stop(self):
        self.running = False

    def run(self):
        try:
            import pyaudiowpatch as pyaudio
            import numpy as np
        except Exception as e:
            self.ui.audio(f"بلا صوت (مكتبة الصوت غير مثبتة: {e})")
            return
        p = pyaudio.PyAudio()
        try:
            dev = p.get_default_wasapi_loopback()
            rate = int(dev["defaultSampleRate"])
            ch_in = int(dev["maxInputChannels"])
            ch_out = 2 if ch_in >= 2 else 1
            frames = rate // 50  # ٢٠ مللي ثانية
            stream = p.open(format=pyaudio.paInt16, channels=ch_in, rate=rate, input=True,
                            input_device_index=dev["index"], frames_per_buffer=frames)
            self.conn.send(MSG_AUDIO_CONFIG, json.dumps({"sampleRate": rate, "channels": ch_out}).encode("utf-8"))
            self.ui.audio(f"يعمل ({rate} هرتز، {ch_out} قناة) — {dev['name']}")
            pts = 0
            while self.running:
                raw = stream.read(frames, exception_on_overflow=False)
                if ch_in != ch_out:
                    a = np.frombuffer(raw, dtype=np.int16).reshape(-1, ch_in)[:, :ch_out]
                    raw = np.ascontiguousarray(a).tobytes()
                self.conn.send(MSG_AUDIO, raw, 0, pts)
                pts += 20_000
            stream.stop_stream(); stream.close()
        except Exception as e:
            if self.running:
                self.ui.audio(f"تعطّل الصوت: {e}")
        finally:
            p.terminate()


# ---------------- الواجهة ----------------
SCREEN_BG = "#0F1F3D"   # خلفية النافذة (كحلي)
FRAME_BG = "#F6F3EA"    # داخل البرواز (فاتح)
FRAME_BORDER = "#C9A227"  # حدّ البرواز (ذهبي)


class App:
    def __init__(self, root):
        self.root = root
        root.title(f"{APP_NAME} - Sender — النسخة {VERSION}")
        root.geometry("680x500")
        root.resizable(False, False)
        root.configure(bg=SCREEN_BG)
        self.receivers = {}   # key -> (name, host, port)
        self.sender = None
        self.discovery = None

        st = ttk.Style()
        st.configure("Card.TFrame", background=FRAME_BG)
        st.configure("Card.TLabel", background=FRAME_BG, font=("Segoe UI", 10))
        st.configure("Title.TLabel", background=FRAME_BG, font=("Segoe UI", 16, "bold"))
        st.configure("Dim.TLabel", background=FRAME_BG, foreground="gray")

        # البرواز: إطار فاتح بحدّ ذهبي داخل خلفية كحلية
        outer = tk.Frame(root, bg=FRAME_BG, highlightbackground=FRAME_BORDER, highlightcolor=FRAME_BORDER, highlightthickness=3)
        outer.pack(fill="both", expand=True, padx=14, pady=14)
        f = ttk.Frame(outer, padding=12, style="Card.TFrame")
        f.pack(fill="both", expand=True)

        head = ttk.Frame(f, style="Card.TFrame"); head.pack(fill="x")
        ttk.Label(head, text="Ready to Send", style="Title.TLabel").pack(side="right")
        ttk.Button(head, text="خروج", command=self.on_close).pack(side="left")

        ttk.Label(f, text="المستقبلات في الشبكة (افتح «Jifco Cast - Receiver» على كروم كاست):", anchor="e", justify="right", style="Card.TLabel").pack(fill="x", pady=(8, 0))
        self.listbox = tk.Listbox(f, height=6, justify="right", font=("Segoe UI", 11))
        self.listbox.pack(fill="x", pady=4)

        row = ttk.Frame(f, style="Card.TFrame"); row.pack(fill="x", pady=2)
        self.scan_btn = ttk.Button(row, text="ابحث عن المستقبلات", command=self.scan)
        self.scan_btn.pack(side="right", padx=2)
        ttk.Label(row, text="أو عنوان يدوي:", style="Card.TLabel").pack(side="right", padx=4)
        self.manual = ttk.Entry(row, width=18, justify="left")
        self.manual.pack(side="right")
        ttk.Button(row, text="أضف", command=self.add_manual).pack(side="right", padx=2)

        row2 = ttk.Frame(f, style="Card.TFrame"); row2.pack(fill="x", pady=2)
        ttk.Label(row2, text="الشاشة:", style="Card.TLabel").pack(side="right", padx=4)
        self.monitor_var = tk.StringVar()
        self.monitor_box = ttk.Combobox(row2, textvariable=self.monitor_var, state="readonly", width=22, justify="right")
        self.monitor_box.pack(side="right")
        self.fill_monitors()
        ttk.Label(row2, text="الدقة:", style="Card.TLabel").pack(side="right", padx=(12, 4))
        self.quality_var = tk.StringVar()
        self.quality_box = ttk.Combobox(row2, textvariable=self.quality_var, state="readonly", width=28, justify="right",
                                        values=[q[0] for q in QUALITIES])
        self.quality_box.pack(side="right")
        self.quality_box.current(0)

        row2b = ttk.Frame(f, style="Card.TFrame"); row2b.pack(fill="x", pady=2)
        self.cursor_var = tk.BooleanVar(value=True)
        st.configure("Card.TCheckbutton", background=FRAME_BG)
        ttk.Checkbutton(row2b, text="إظهار مؤشر الماوس في البث", variable=self.cursor_var, style="Card.TCheckbutton").pack(side="right")
        ttk.Label(row2b, text="كلمة سر المستقبل:", style="Card.TLabel").pack(side="right", padx=(16, 4))
        self.pin_var = tk.StringVar(value=DEFAULT_PIN)
        ttk.Entry(row2b, textvariable=self.pin_var, width=10, show="•", justify="center").pack(side="right")
        self.settings = load_settings()
        self.listbox.bind("<<ListboxSelect>>", self.on_select_receiver)

        row3 = ttk.Frame(f, style="Card.TFrame"); row3.pack(fill="x", pady=8)
        self.cast_btn = ttk.Button(row3, text="عكس الشاشة", command=self.cast)
        self.cast_btn.pack(side="right", padx=2)
        self.stop_btn = ttk.Button(row3, text="إيقاف", command=self.stop, state="disabled")
        self.stop_btn.pack(side="right", padx=2)
        ttk.Label(row3, text="وضع العرض:", style="Card.TLabel").pack(side="right", padx=(16, 4))
        self.mode_btns = []
        for label, mode in (("ملاءمة", "fit"), ("ملء", "fill"), ("تمديد", "stretch")):
            b = ttk.Button(row3, text=label, command=lambda m=mode: self.set_mode(m), state="disabled")
            b.pack(side="right", padx=1)
            self.mode_btns.append(b)

        row4 = ttk.Frame(f, style="Card.TFrame"); row4.pack(fill="x", pady=2)
        self.delay_var = tk.IntVar(value=int(load_settings().get("audio_delay", 0)))
        self.delay_label = ttk.Label(row4, text=self.sync_text(self.delay_var.get()), style="Card.TLabel")
        self.delay_label.pack(side="right", padx=4)
        self.delay_btns = []
        for label, d in (("− ٥٠", -50), ("+ ٥٠", 50), ("صفر", None)):
            b = ttk.Button(row4, text=label, command=lambda d=d: self.change_delay(d), state="disabled")
            b.pack(side="right", padx=1)
            self.delay_btns.append(b)

        self.status_var = tk.StringVar(value="اضغط «ابحث عن المستقبلات»")
        ttk.Label(f, textvariable=self.status_var, anchor="e", justify="right", wraplength=600, style="Card.TLabel").pack(fill="x", pady=(8, 2))
        self.stats_var = tk.StringVar(value="")
        ttk.Label(f, textvariable=self.stats_var, anchor="e", style="Card.TLabel").pack(fill="x")
        self.audio_var = tk.StringVar(value="")
        ttk.Label(f, textvariable=self.audio_var, anchor="e", wraplength=600, justify="right", style="Card.TLabel").pack(fill="x")
        ttk.Label(f, text=f"{APP_NAME} · النسخة {VERSION}", anchor="center", style="Dim.TLabel").pack(side="bottom", fill="x", pady=(10, 0))
        root.protocol("WM_DELETE_WINDOW", self.on_close)

    # ----- مساعدة للخيوط -----
    def status(self, text): self.root.after(0, lambda: self.status_var.set(text))
    def stats(self, frames, kbps): self.root.after(0, lambda: self.stats_var.set(f"الإطارات {frames} · {kbps} كيلوبت/ث"))
    def audio(self, text): self.root.after(0, lambda: self.audio_var.set(f"الصوت: {text}"))
    def streaming(self, on):
        def apply():
            self.cast_btn.config(state="disabled" if on else "normal", text="جارٍ العكس…" if on else "عكس الشاشة")
            self.stop_btn.config(state="normal" if on else "disabled")
            for b in self.mode_btns + self.delay_btns: b.config(state="normal" if on else "disabled")
            if on and self.sender and self.delay_var.get() != 0:
                self.sender.set_audio_delay(self.delay_var.get())   # إرسال التأخير المحفوظ عند بدء البث
            if not on:
                self.stats_var.set("")
        self.root.after(0, apply)

    def pin_ok(self, host, pin):
        """تُحفظ كلمة السر الصحيحة لكل مستقبل."""
        self.settings.setdefault("pins", {})[host] = pin
        save_settings(self.settings)

    def on_select_receiver(self, _=None):
        sel = self.listbox.curselection()
        if not sel:
            return
        _, host, _ = list(self.receivers.values())[sel[0]]
        self.pin_var.set(self.settings.get("pins", {}).get(host, DEFAULT_PIN))

    def fill_monitors(self):
        try:
            import mss
            with mss.mss() as sct:
                mons = sct.monitors[1:]
            vals = [f"الشاشة {i+1} — {m['width']}×{m['height']}" for i, m in enumerate(mons)]
        except Exception as e:
            vals = [f"تعذر قراءة الشاشات: {e}"]
        self.monitor_box["values"] = vals
        if vals:
            self.monitor_box.current(0)

    def scan(self):
        self.scan_btn.config(state="disabled", text="جارٍ البحث (٧ ثوانٍ)…")
        self.listbox.delete(0, "end"); self.receivers.clear()
        self.status_var.set("جارٍ البحث في الشبكة…")
        if self.discovery:
            self.discovery.stop()
        try:
            self.discovery = Discovery(self.found)
            self.discovery.start()
        except Exception as e:
            self.status_var.set(f"تعذر البحث: {e}")
            self.scan_btn.config(state="normal", text="ابحث عن المستقبلات")
            return
        self.root.after(7000, self.scan_done)

    def scan_done(self):
        self.scan_btn.config(state="normal", text="ابحث عن المستقبلات")
        n = len(self.receivers)
        self.status_var.set(f"وُجد {n} مستقبل" if n else "لم يُعثر على مستقبل — تأكد أن «عاكس — مستقبل» مفتوح على كروم كاست، أو أدخل العنوان يدوياً")

    def found(self, name, host, port):
        def apply():
            key = f"{host}:{port}"
            if key in self.receivers:
                return
            self.receivers[key] = (name, host, port)
            self.listbox.insert("end", f"{name}  —  {host}:{port}")
            self.status_var.set(f"وُجد: {name}")
        self.root.after(0, apply)

    def add_manual(self):
        txt = self.manual.get().strip()
        if not txt:
            return
        host, _, port = txt.partition(":")
        port = int(port) if port.isdigit() else DEFAULT_PORT
        self.found("عنوان يدوي", host, port)

    def cast(self):
        sel = self.listbox.curselection()
        if not sel:
            messagebox.showinfo(APP_NAME, "اختر مستقبلاً من القائمة أولاً")
            return
        name, host, port = list(self.receivers.values())[sel[0]]
        mon = (self.monitor_box.current() or 0) + 1
        self.cast_btn.config(state="disabled", text="جارٍ الاتصال…")
        _, long_side, bitrate = QUALITIES[self.quality_box.current() or 0]
        self.sender = Sender(host, port, mon, self, long_side, bitrate, self.cursor_var.get(), self.pin_var.get().strip() or DEFAULT_PIN)
        self.sender.start()

    def stop(self):
        if self.sender:
            self.sender.stop()
        self.status_var.set("أُوقف البث")

    def set_mode(self, mode):
        if self.sender:
            self.sender.set_mode(mode)

    @staticmethod
    def sync_text(v):
        # قيمة موقّعة: الموجب يؤخّر الصوت، والسالب يؤخّر الصورة في المستقبل
        num = "\u2066" + ("−" if v < 0 else "+" if v > 0 else "") + str(abs(v)) + "\u2069"
        what = "الصورة تتأخر" if v < 0 else "الصوت يتأخر" if v > 0 else "بلا تأخير"
        return f"تزامن الصوت: {num} مللي ثانية ({what}):"

    def change_delay(self, d):
        v = 0 if d is None else max(-1000, min(2000, self.delay_var.get() + d))
        self.delay_var.set(v)
        self.delay_label.config(text=self.sync_text(v))
        self.settings["audio_delay"] = v
        save_settings(self.settings)
        if self.sender:
            self.sender.set_audio_delay(v)

    def on_close(self):
        self.stop()
        if self.discovery:
            self.discovery.stop()
        self.root.after(300, self.root.destroy)


if __name__ == "__main__":
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", 1.25)
    except Exception:
        pass
    App(root)
    root.mainloop()
