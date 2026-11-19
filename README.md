# ClipForge AI

**Long videos in. Short clips out.**

A local-first video clipping MVP. Choose a video, clip length, aspect ratio and resolution, then export the generated clips as a ZIP file. Video processing runs on your own computer using FFmpeg.

## Included in this first version
- Upload a local video (MP4, MOV, MKV, WEBM, AVI, M4V)
- Generate evenly spaced 15, 30, 45 or 60-second clips
- Export vertical (9:16), square (1:1) or landscape (16:9)
- Export 720p or 1080p
- Download all clips as a ZIP
- Local-only web interface and processing server

**Not implemented yet:** AI highlight selection, Urdu/English speech-to-text captions, speaker tracking, smart face reframing, silence removal and 4K export. These are planned next stages, not active features in this MVP.

## Requirements
- Windows 10/11, macOS or Linux
- Python 3.10+
- FFmpeg and FFprobe installed and available in PATH

Check FFmpeg in Command Prompt:
```bash
ffmpeg -version
ffprobe -version
```

## Run on Windows
1. Install Python from https://www.python.org/downloads/ (enable **Add Python to PATH**).
2. Install FFmpeg and add its `bin` folder to PATH.
3. Download this repository as ZIP and extract it.
4. Open Command Prompt in the extracted folder.
5. Run:
   ```bat
   py -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements.txt
   py app.py
   ```
6. Open http://127.0.0.1:8000

## Privacy and safety
The server binds only to `127.0.0.1`, so it is intended for use on your own computer. Uploaded files are stored locally in `work/`. Do not expose this development server to the public internet. Only edit and publish videos you own or have permission to use.

## Project status
Version 0.1.0: functional local clipping foundation. See the repository issues/README as the project grows.
