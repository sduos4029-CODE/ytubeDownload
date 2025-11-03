from flask import Flask, request, render_template, jsonify
import yt_dlp, threading, re, os, subprocess, tempfile, time

app = Flask(__name__)

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

def unique_filename(base, ext):
    candidate = f"{base}.{ext}"
    counter = 1
    while os.path.exists(candidate):
        candidate = f"{base} ({counter}).{ext}"
        counter += 1
    return candidate

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

def build_filename(title, vfmt=None, afmt=None, is_video=True):
    safe_title = sanitize_filename(title)
    parts = [safe_title]
    if is_video and vfmt:
        res = vfmt.get("resolution") or (f"{vfmt.get('height')}p" if vfmt.get('height') else None)
        if res: parts.append(res)
    if afmt:
        abr = afmt.get("abr")
        abr_label = f"{abr}kbps" if isinstance(abr, int) or (isinstance(abr, str) and abr.isdigit()) else str(abr or "")
        if abr_label: parts.append(abr_label)
    ext = "mp4" if is_video else "mp3"
    return unique_filename("_".join([p for p in parts if p]), ext)

# ---------------- Hooks ----------------
def make_hook(phase):
    def hook(d):
        global last_filename, cancel_active
        if cancel_active:
            raise yt_dlp.utils.DownloadCancelled()

        if d.get("filename"):
            last_filename = d["filename"]

        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        downloaded = d.get("downloaded_bytes")
        try:
            percent = f"{(downloaded/total)*100:.1f}%" if total and downloaded else "0%"
        except:
            percent = "0%"

        progress_state[phase] = {
            "status": d.get("status") or "",
            "eta": format_eta(d.get("eta")),
            "speed": format_speed(d.get("speed")),
            "percent": percent,
            "size": fmt_size(total)
        }
    return hook

# ---------------- Routes ----------------
@app.route("/")
def index():
    return render_template("index.html")

@app.route("/fetch", methods=["POST"])
def fetch():
    reset_progress()
    global cancel_active
    cancel_active = False
    url = (request.get_json() or {}).get("url","").strip()
    try:
        with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            video_info.clear()
            video_info["title"] = info.get("title") or ""
            video_info["thumbnail"] = info.get("thumbnail") or ""
            # collect video formats (video-containing)
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
            # collect audio-only formats
            video_info["audio_formats"] = [
                {
                    "format_id": f.get("format_id"),
                    "abr": f.get("abr"),
                    "ext": f.get("ext"),
                    "acodec": f.get("acodec"),
                    "filesize": f.get("filesize") or f.get("filesize_approx")
                }
                for f in info.get("formats", [])
                if f.get("vcodec") == "none" and f.get("acodec") != "none"
            ]
            # sort audio_formats descending by abr when available
            video_info["audio_formats"].sort(key=lambda x: (x.get("abr") or 0), reverse=True)
    except Exception as e:
        print("Error in /fetch:", e)
        video_info.clear()
    return jsonify({"status": "info_fetched"})

@app.route("/info")
def info():
    return jsonify(video_info)

@app.route("/download_audio", methods=["POST"])
def download_audio():
    global cancel_active, last_filename
    cancel_active = False
    reset_progress()
    data = request.get_json() or {}
    url = (data.get("url") or "").strip()
    audio_id = data.get("audio_id") or "bestaudio"
    force_mp3 = bool(data.get("force_mp3", False))
    lossless_only = bool(data.get("lossless_only", False))
    requested_format = data.get("format")  # optional explicit format

    # Probe to detect bitrate (abr) for naming/format decision
    try:
        with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            fmt = next((f for f in info.get("formats", []) if f.get("format_id") == audio_id), {})
            abr = fmt.get("abr") or 0
            # normalize to int if possible
            try:
                abr = int(abr)
            except:
                abr = 0
    except Exception as e:
        print("Bitrate detection failed:", e)
        abr = 0

    # Decide output format
    if requested_format:
        output_format = requested_format
    elif force_mp3:
        output_format = "mp3"
    elif lossless_only:
        output_format = "flac"
    else:
        if abr >= 256:
            output_format = "flac"
        elif abr >= 192:
            output_format = "mp3"
        else:
            output_format = "ogg"

    abr_label = f"{abr}kbps" if abr else "best"
    outtmpl = build_filename(video_info.get("title", "audio"), afmt={"abr": abr_label}, is_video=False)
    outtmpl = outtmpl.rsplit(".", 1)[0] + f".{output_format}"

    if os.path.exists(outtmpl):
        size = os.path.getsize(outtmpl)
        progress_state["audio"] = {"status":"finished","eta":"Done","speed":"—","percent":"100%","size":fmt_size(size)}
        last_filename = outtmpl
        return jsonify({"status":"already_done"})

    temp_file = tempfile.mktemp(suffix=".m4a")

    def run_audio():
        try:
            # download chosen audio stream to temp file
            ydl_opts = {
                "quiet": True,
                "progress_hooks": [make_hook("audio")],
                "outtmpl": temp_file,
                "format": audio_id,
                "overwrites": True
            }
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([url])

            # convert to desired format
            codec_map = {
                "mp3": ["-acodec", "libmp3lame", "-q:a", "0"],
                "webm": ["-acodec", "libvorbis"],
                "ogg": ["-acodec", "libvorbis"],
                "flac": ["-acodec", "flac"],
                "wav": ["-acodec", "pcm_s16le"]
            }
            ffmpeg_args = codec_map.get(output_format, ["-acodec", "libmp3lame", "-q:a", "0"])

            subprocess.run(["ffmpeg", "-y", "-i", temp_file, *ffmpeg_args, outtmpl],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            progress_state["audio"] = {
                "status": "finished",
                "eta": "Done",
                "speed": "—",
                "percent": "100%",
                "size": fmt_size(os.path.getsize(outtmpl))
            }
            last_filename = outtmpl

        except yt_dlp.utils.DownloadCancelled:
            progress_state["audio"]["status"] = "cancelled"
        except Exception as e:
            print("Audio download/convert failed:", e)
            progress_state["audio"]["status"] = "error"
        finally:
            try: os.remove(temp_file)
            except: pass

    threading.Thread(target=run_audio, daemon=True).start()
    return jsonify({"status": "started"})

@app.route("/download_video", methods=["POST"])
def download_video():
    global cancel_active, last_filename
    cancel_active = False
    reset_progress()
    data = request.get_json() or {}
    url = (data.get("url") or "").strip()
    video_id, audio_id = data.get("video_id"), data.get("audio_id")

    vfmt = next((f for f in video_info.get("video_formats", []) if f.get("format_id") == video_id), {})
    afmt = next((f for f in video_info.get("audio_formats", []) if f.get("format_id") == audio_id), {})
    final_file = build_filename(video_info.get("title","video"), vfmt=vfmt, afmt=afmt, is_video=True)

    if os.path.exists(final_file):
        size = os.path.getsize(final_file)
        for phase in ["video","audio","merge"]:
            progress_state[phase] = {"status":"finished","eta":"Done","speed":"—","percent":"100%","size":fmt_size(size)}
        last_filename = final_file
        return jsonify({"status":"already_done"})

    tmp_video = tempfile.mktemp(suffix=".mp4")
    tmp_audio = tempfile.mktemp(suffix=".m4a")

    def run_video():
        try:
            # download video and audio in parallel
            def dl_video():
                v_opts = {"quiet": True, "progress_hooks":[make_hook("video")], "outtmpl": tmp_video, "format": video_id or "bestvideo"}
                with yt_dlp.YoutubeDL(v_opts) as ydl:
                    ydl.download([url])

            def dl_audio():
                a_opts = {"quiet": True, "progress_hooks":[make_hook("audio")], "outtmpl": tmp_audio, "format": audio_id or "bestaudio"}
                with yt_dlp.YoutubeDL(a_opts) as ydl:
                    ydl.download([url])

            t1 = threading.Thread(target=dl_video, daemon=True)
            t2 = threading.Thread(target=dl_audio, daemon=True)
            t1.start(); t2.start()
            t1.join(); t2.join()

            # merge
            progress_state["merge"] = {"status":"merging","eta":"—","speed":"—","percent":"0%","size":"—"}

            subprocess.run([
                "ffmpeg", "-y",
                "-i", tmp_video,
                "-i", tmp_audio,
                "-c", "copy", final_file
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            progress_state["merge"] = {
                "status": "finished",
                "eta": "Done",
                "speed": "—",
                "percent": "100%",
                "size": fmt_size(os.path.getsize(final_file))
            }
            last_filename = final_file

        except yt_dlp.utils.DownloadCancelled:
            progress_state["video"]["status"] = "cancelled"
            progress_state["audio"]["status"] = "cancelled"
            progress_state["merge"]["status"] = "cancelled"
        except Exception as e:
            print("Video download/merge failed:", e)
            progress_state["merge"]["status"] = "error"
        finally:
            for f in [tmp_video, tmp_audio]:
                try: os.remove(f)
                except: pass

    threading.Thread(target=run_video, daemon=True).start()
    return jsonify({"status": "started"})

@app.route("/progress")
def progress():
    # Return a shallow copy to avoid accidental mutation in client handling
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
    reset_progress()
    video_info.clear()
    global cancel_active
    cancel_active = False
    return jsonify({"status": "reset_done"})

if __name__ == "__main__":
    reset_progress()
    app.run(debug=True, threaded=True)
