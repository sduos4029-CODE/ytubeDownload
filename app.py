
from flask import Flask, render_template, request, jsonify
import yt_dlp
import os
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
import time
import re

app = Flask(__name__)

# Global progress tracker
download_progress = {}

# ----------------------------
# Utility
# ----------------------------
def sanitize_filename(filename):
    """Remove or replace invalid filename characters."""
    return re.sub(r'[<>:"/\\|?*\x00-\x1F]', '', filename).strip().replace(":", "-")

# ----------------------------
# Routes
# ----------------------------
@app.route('/')
def index():
    return render_template('index.html')

@app.route('/get_video_info', methods=['POST'])
def get_video_info():
    """Fetch video metadata and available formats using yt_dlp."""
    try:
        url = request.json.get('url')
        if not url:
            return jsonify({'success': False, 'error': 'Missing video URL'})

        ydl_opts = {'quiet': True}
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            result = ydl.extract_info(url, download=False)

        formats = []
        for f in result.get('formats', []):
            format_type = (
                "Video+Audio" if f.get("vcodec") != "none" and f.get("acodec") != "none"
                else "Audio Only" if f.get("acodec") != "none"
                else "Video Only"
            )

            filesize = f.get('filesize') or f.get('filesize_approx')
            if filesize:
                filesize_mb = round(filesize / (1024 * 1024), 2)
                filesize_str = f"{filesize_mb} MB"
            else:
                filesize_str = 'N/A'

            formats.append({
                'format_id': f['format_id'],
                'ext': f.get('ext', ''),
                'resolution': f.get('resolution', 'N/A'),
                'fps': f.get('fps', 'N/A'),
                'filesize': filesize_str,
                'type': format_type
            })

        return jsonify({
            'success': True,
            'title': result.get('title', 'Unknown'),
            'thumbnail': result.get('thumbnail', ''),
            'formats': formats
        })

    except Exception as e:
        return jsonify({'success': False, 'error': str(e)})

# ----------------------------
# Progress tracking
# ----------------------------
def progress_hook(d, download_id, stream_type):
    if d['status'] == 'downloading':
        total = d.get('total_bytes') or d.get('total_bytes_estimate', 0)
        downloaded = d.get('downloaded_bytes', 0)
        speed = d.get('speed', 0)
        eta = d.get('eta', 0)

        percent = (downloaded / total * 100) if total > 0 else 0
        download_progress[download_id][stream_type] = {
            'status': 'downloading',
            'percent': round(percent, 1),
            'downloaded_mb': round(downloaded / (1024 * 1024), 2),
            'total_mb': round(total / (1024 * 1024), 2),
            'speed_mbps': round((speed or 0) / (1024 * 1024), 2),
            'eta': eta
        }

    elif d['status'] == 'finished':
        download_progress[download_id][stream_type] = {
            'status': 'finished',
            'percent': 100
        }

# ----------------------------
# Download handling
# ----------------------------
def download_stream(url, format_spec, stream_type, download_id, output_path):
    """Download a single stream (video or audio)."""
    try:
        ydl_opts = {
            'format': format_spec,
            'outtmpl': output_path,
            'quiet': True,
            'no_warnings': True,
            'progress_hooks': [lambda d: progress_hook(d, download_id, stream_type)]
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        download_progress[download_id][stream_type]['status'] = 'complete'
        return True
    except Exception as e:
        download_progress[download_id][stream_type] = {'status': 'error', 'error': str(e)}
        return False

@app.route('/download', methods=['POST'])
def download():
    """Start download (in background thread)."""
    download_id = str(int(time.time() * 1000))
    data = request.get_json()  # ✅ Copy data before starting thread

    def download_task():
        try:
            url = data.get('url')
            format_id = data.get('format_id')
            format_type = data.get('format_type')
            title = sanitize_filename(data.get('title', 'video'))
            resolution = data.get('resolution', '')
            custom_path = data.get('custom_path', '').strip()

            if not url or not format_id:
                download_progress[download_id] = {
                    'video': {'status': 'error', 'error': 'Missing URL or format ID'},
                    'audio': {'status': 'error'},
                    'merge': {'status': 'error'}
                }
                return

            save_path = custom_path if custom_path else '.'
            os.makedirs(save_path, exist_ok=True)

            # Initialize progress states
            download_progress[download_id] = {
                'video': {'status': 'pending'},
                'audio': {'status': 'pending'},
                'merge': {'status': 'pending'}
            }

            # --- Video Only → needs merge ---
            if format_type == "Video Only":
                temp_video = os.path.join(save_path, f'temp_video_{download_id}.mp4')
                temp_audio = os.path.join(save_path, f'temp_audio_{download_id}.mp3')

                with ThreadPoolExecutor(max_workers=2) as executor:
                    video_future = executor.submit(download_stream, url, format_id, 'video', download_id, temp_video)
                    audio_future = executor.submit(download_stream, url, 'bestaudio[ext=m4a]/bestaudio', 'audio', download_id, temp_audio)

                    video_ok = video_future.result()
                    audio_ok = audio_future.result()

                if not video_ok or not audio_ok:
                    download_progress[download_id]['merge'] = {'status': 'error', 'error': 'Video or audio download failed'}
                    return

                output_file = os.path.join(save_path, f"{title} - {resolution}.mp4")
                download_progress[download_id]['merge'] = {'status': 'merging', 'percent': 50}

                cmd = f'ffmpeg -i "{temp_video}" -i "{temp_audio}" -c:v copy -c:a aac "{output_file}" -y -loglevel error'
                process = subprocess.Popen(cmd, shell=True)
                process.wait()

                # Cleanup
                for f in (temp_video, temp_audio):
                    if os.path.exists(f): os.remove(f)

                if process.returncode == 0:
                    download_progress[download_id]['merge'] = {
                        'status': 'complete',
                        'percent': 100,
                        'filename': output_file
                    }
                else:
                    download_progress[download_id]['merge'] = {'status': 'error', 'error': 'ffmpeg merge failed'}

            # --- Full Video (no merge needed) ---
            else:
                output_template = os.path.join(save_path, f"{title} - {resolution}.%(ext)s")
                ydl_opts = {
                    'format': format_id,
                    'outtmpl': output_template,
                    'quiet': True,
                    'progress_hooks': [lambda d: progress_hook(d, download_id, 'video')]
                }

                download_progress[download_id] = {
                    'video': {'status': 'pending'},
                    'audio': {'status': 'not_needed'},
                    'merge': {'status': 'not_needed'}
                }

                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                    final_filename = ydl.prepare_filename(info)

                download_progress[download_id]['video'] = {
                    'status': 'complete',
                    'percent': 100,
                    'filename': final_filename
                }

        except Exception as e:
            download_progress[download_id] = {
                'video': {'status': 'error', 'error': str(e)},
                'audio': {'status': 'error'},
                'merge': {'status': 'error'}
            }

    # Run task in background
    thread = threading.Thread(target=download_task)
    thread.start()

    return jsonify({'success': True, 'download_id': download_id})

@app.route('/progress/<download_id>')
def get_progress(download_id):
    """Return current progress of given download."""
    return jsonify(download_progress.get(download_id, {}))

# ----------------------------
# Main entry
# ----------------------------
if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)
