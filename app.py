from flask import Flask, request, render_template, jsonify, send_file
import yt_dlp, threading, re, os, subprocess, tempfile, socket
from pathlib import Path

app = Flask(__name__, template_folder="templates")

progress_state = {}
video_info = {}
last_filename = ""
cancel_active = False

# ---------------- Helpers ----------------
def reset_progress():
    global progress_state, last_filename
    progress_state = {
        "video": {"status": "", "eta": "—", "speed": "—", "percent": "0%", "size": "—"},
        "audio": {"status": "", "eta": "—", "speed": "—", "percent": "0%", "size": "—"},
        "merge": {"status": "", "eta": "—", "speed": "—", "percent": "0%", "size": "—"},
    }
    last_filename = ""

def sanitize_filename(name: str) -> str:
    if not name:
        return "download"
    return re.sub(r'[\\/*?:"<>|]', "_", name)

def fmt_size(b):
    if not b: return "—"
    try:
        b = int(b)
        kb, mb, gb = b/1024, b/1024**2, b/1024**3
        if gb >= 1: return f"{gb:.2f} GB"
        if mb >= 1: return f"{mb:.1f} MB"
        return f"{int(kb)} KB"
    except:
        return "—"

def format_speed(speed):
    if not speed: return "—"
    try:
        mb = float(speed) / (1024**2)
        return f"{mb:.1f} MB/s"
    except:
        return "—"

def format_eta(eta):
    if eta is None: return "—"
    try:
        m, s = divmod(int(eta), 60)
        h, m = divmod(m, 60)
        return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"
    except:
        return "—"

def unique_filename(base, ext):
    candidate = f"{base}.{ext}"
    counter = 1
    while os.path.exists(candidate):
        candidate = f"{base} ({counter}).{ext}"
        counter += 1
    return candidate

def make_hook(phase):
    def hook(d):
        global cancel_active, last_filename
        if cancel_active:
            raise yt_dlp.utils.DownloadCancelled()

        if d.get("filename"):
            last_filename = d["filename"]

        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        downloaded = d.get("downloaded_bytes", 0)
        try:
            percent = f"{(downloaded / total) * 100:.1f}%" if total else "0%"
        except:
            percent = "0%"

        progress_state[phase] = {
            "status": d.get("status", ""),
            "eta": format_eta(d.get("eta")),
            "speed": format_speed(d.get("speed")),
            "percent": percent,
            "size": fmt_size(total),
        }
    return hook

# ---------------- Routes ----------------
@app.route("/")
def index():
    tpl = Path("templates/index.html")
    if tpl.exists():
        return render_template("index.html")
    return "Place your index.html inside templates folder.", 404

@app.route("/fetch", methods=["POST"])
def fetch():
    reset_progress()
    global cancel_active
    cancel_active = False
    url = (request.get_json() or {}).get("url", "").strip()
    try:
        with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            video_info.clear()
            video_info["title"] = info.get("title") or "video"
            video_info["thumbnail"] = info.get("thumbnail") or ""
            video_info["video_formats"] = [
                {
                    "format_id": f.get("format_id"),
                    "ext": f.get("ext"),
                    "resolution": f.get("resolution") or (f"{f.get('height')}p" if f.get("height") else None),
                    "height": f.get("height"),
                    "size": f.get("filesize") or f.get("filesize_approx")
                }
                for f in info.get("formats", [])
                if f.get("vcodec") != "none" and f.get("ext") in ("mp4","webm","mkv")
            ]
            video_info["audio_formats"] = [
                {
                    "format_id": f.get("format_id"),
                    "abr": f.get("abr"),
                    "ext": f.get("ext"),
                    "filesize": f.get("filesize") or f.get("filesize_approx")
                }
                for f in info.get("formats", [])
                if f.get("vcodec") == "none" and f.get("acodec") != "none"
            ]
    except Exception as e:
        print("Fetch error:", e)
        video_info.clear()
    return jsonify({"status": "info_fetched"})

@app.route("/info")
def info():
    return jsonify(video_info)

# ---------------- Download Audio ----------------
@app.route("/download_audio", methods=["POST"])
def download_audio():
    global cancel_active, last_filename
    cancel_active = False
    reset_progress()
    data = request.get_json() or {}
    url = (data.get("url") or "").strip()
    fmt_id = "bestaudio"

    try:
        with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            afmt = next((f for f in info.get("formats", []) if f.get("vcodec") == "none"), {})
            abr = afmt.get("abr") or 0
            ext = afmt.get("ext") or "mp3"
            title = sanitize_filename(info.get("title", "audio"))
            base_name = f"{title}_{abr}kbps"
            out_file = unique_filename(base_name, ext)
    except Exception:
        out_file = "audio.mp3"

    tmp = tempfile.mktemp(suffix=".m4a")

    def run_audio():
        try:
            ydl_opts = {
                "quiet": True,
                "progress_hooks": [make_hook("audio")],
                "outtmpl": tmp,
                "format": fmt_id,
            }
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([url])
            subprocess.run(
                ["ffmpeg", "-y", "-i", tmp, "-vn", "-acodec", "copy", out_file],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
            progress_state["audio"] = {"status":"finished","eta":"Done","speed":"—","percent":"100%","size":fmt_size(size)}
            last_filename = out_file
        except yt_dlp.utils.DownloadCancelled:
            progress_state["audio"]["status"] = "cancelled"
        except Exception as e:
            print("Audio error:", e)
            progress_state["audio"]["status"] = "error"
        finally:
            if os.path.exists(tmp): os.remove(tmp)

    threading.Thread(target=run_audio, daemon=True).start()
    return jsonify({"status": "started"})

# ---------------- Download Video ----------------
@app.route("/download_video", methods=["POST"])
def download_video():
    global cancel_active, last_filename
    cancel_active = False
    reset_progress()
    data = request.get_json() or {}
    url = (data.get("url") or "").strip()

    try:
        with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            title = sanitize_filename(info.get("title", "video"))
            vfmt = next((f for f in info["formats"] if f.get("vcodec") != "none"), {})
            afmt = next((f for f in info["formats"] if f.get("vcodec") == "none"), {})
            res = vfmt.get("resolution") or (f"{vfmt.get('height')}p" if vfmt.get("height") else "")
            abr = afmt.get("abr") or 0
            ext = vfmt.get("ext") or "mp4"
            base_name = f"{title}_{res}_{abr}kbps"
            out_file = unique_filename(base_name, ext)
    except Exception:
        out_file = "video.mp4"

    tmpv, tmpa = tempfile.mktemp(suffix=".mp4"), tempfile.mktemp(suffix=".m4a")

    def run_video():
        try:
            def download_video_part():
                opts = {"quiet": True, "progress_hooks":[make_hook("video")],"outtmpl": tmpv,"format":"bestvideo"}
                with yt_dlp.YoutubeDL(opts) as ydl: ydl.download([url])

            def download_audio_part():
                opts = {"quiet": True, "progress_hooks":[make_hook("audio")],"outtmpl": tmpa,"format":"bestaudio"}
                with yt_dlp.YoutubeDL(opts) as ydl: ydl.download([url])

            t1 = threading.Thread(target=download_video_part)
            t2 = threading.Thread(target=download_audio_part)
            t1.start(); t2.start(); t1.join(); t2.join()

            progress_state["merge"] = {"status": "merging","eta": "—","speed": "—","percent": "80%","size": "—"}
            subprocess.run(
                ["ffmpeg", "-y", "-i", tmpv, "-i", tmpa, "-c", "copy", out_file],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
            progress_state["merge"] = {"status":"finished","eta":"Done","speed":"—","percent":"100%","size":fmt_size(size)}
            last_filename = out_file
        except yt_dlp.utils.DownloadCancelled:
            progress_state["video"]["status"] = "cancelled"
        except Exception as e:
            print("Video error:", e)
            progress_state["merge"]["status"] = "error"
        finally:
            for f in [tmpv, tmpa]:
                if os.path.exists(f): os.remove(f)

    threading.Thread(target=run_video, daemon=True).start()
    return jsonify({"status": "started"})

# ---------------- Utility Endpoints ----------------
@app.route("/progress")
def progress():
    return jsonify(progress_state)

@app.route("/done")
def done():
    return jsonify({"filename": last_filename})

@app.route("/cancel", methods=["POST"])
def cancel():
    global cancel_active
    cancel_active = True
    return jsonify({"status": "cancelled"})

@app.route("/reset", methods=["POST"])
def reset():
    global cancel_active
    cancel_active = False
    reset_progress()
    video_info.clear()
    return jsonify({"status": "reset_done"})

# ---------------- Startup ----------------
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip

if __name__ == "__main__":
    reset_progress()
    host = "0.0.0.0"
    port = int(os.environ.get("PORT", 5000))
    ip = get_local_ip()
    print(f"Running on:\n - http://127.0.0.1:{port}\n - http://{ip}:{port}")
    if ip and ip != "127.0.0.1":
        print(f" - http://{ip}:{port}")
    else:
        print(" - (no LAN IP detected)")
    
    app.run(host=host, port=port, debug=False, threaded=True)
