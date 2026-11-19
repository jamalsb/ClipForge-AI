# ClipForge AI

**Long videos in. Short clips out.**

ClipForge AI is being built as a browser-based video clipping service. The current repository version provides the video-processing API and a mobile-friendly web interface. It can create evenly spaced clips, export each clip in vertical, square and landscape formats in one run, and, when an AI provider key is configured on the server, generate timestamped speech subtitles in the original spoken language.

## Available in this build
- Upload MP4, MOV, MKV, WEBM, AVI or M4V video
- Generate 15, 30, 45 or 60-second clips
- Export vertical (9:16), square (1:1) or landscape (16:9), individually or all three formats for every source clip
- Export 720p or 1080p
- Download clips together as a ZIP, with format labels in each filename
- Optional Urdu/English multilingual speech transcription to an SRT subtitle file
- AI-ranked short highlights selected from the transcript, with title/reason metadata in the ZIP
- Long audio is extracted and transcribed in smaller chunks

## Not available yet
- Translation from one subtitle language to another
- Captions permanently burned into the video
- Automatic face tracking and speaker-aware reframing
- User accounts, cloud job queue and durable object storage

These are planned development stages and are not represented as completed features.

## AI setup
AI subtitles use Groq's Whisper transcription API. The application reads the key from the server environment variable `GROQ_API_KEY`.

Never put an API key in frontend JavaScript, this README, a public GitHub file, or a message. Configure it only as a private environment secret on the server that runs ClipForge.

If the key is not configured, normal clip creation still works. Selecting AI subtitles or AI highlight selection returns a clear setup error rather than pretending AI work was completed.

AI highlight selection reuses the timestamped transcript, asks Groq's chat model to rank short moments, removes overlapping selections, and exports the selected clips. The ZIP includes `highlights.json` with each selected clip title, reason, and source timestamps. The default chat model is `openai/gpt-oss-20b`; set `GROQ_HIGHLIGHT_MODEL` as a private server environment variable to override it.

The transcription model detects the spoken language and returns timestamped segments. The ZIP includes a `subtitles.srt` file. The SRT is a separate subtitle file; this build does not burn captions into the MP4 clips.

## Run locally for development
Requirements:
- Python 3.10+
- FFmpeg and FFprobe installed and available in PATH

Install dependencies:
```bash
py -m venv .venv
.venv\\Scripts\\activate
pip install -r requirements.txt
py app.py
```

Open `http://127.0.0.1:8000`.

For an online service, the API must run on a server with FFmpeg installed. The current build still uses the server's local disk for temporary uploads and outputs. It does not yet include durable cloud storage, a background job queue, or multi-user accounts, so it is a development foundation rather than a production-ready public service.

## Project status
Version 0.3.0 development build. AI speech transcription and transcript-based highlight selection are implemented behind the Groq API key. Full cloud-service infrastructure, translated subtitles, burned-in captions and smart reframing remain in progress.
