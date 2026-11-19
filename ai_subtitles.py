from __future__ import annotations

import os
import subprocess
from pathlib import Path

from fastapi import HTTPException


def _stamp(seconds: float) -> str:
    milliseconds = max(0, round(seconds * 1000))
    hours, remainder = divmod(milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, millis = divmod(remainder, 1000)
    return f"{hours:02}:{minutes:02}:{secs:02},{millis:03}"


def transcribe_video(video_path: Path, output_srt: Path, temp_dir: Path) -> int:
    """Create a timestamped SRT in the video's spoken language using Groq Whisper."""
    api_key = os.getenv("GROQ_API_KEY")
    if not api_key:
        raise HTTPException(
            status_code=503,
            detail="AI subtitles need a GROQ_API_KEY environment secret. Add it to the server settings, then try again."
        )

    try:
        from groq import Groq
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="The AI transcription package is not installed.") from exc

    ffmpeg = shutil_which("ffmpeg")
    if not ffmpeg:
        raise HTTPException(status_code=500, detail="FFmpeg is required for audio extraction.")

    client = Groq(api_key=api_key, timeout=180.0, max_retries=2)
    entries: list[tuple[float, float, str]] = []
    chunk_seconds = 300
    offset = 0.0

    while True:
        audio_path = temp_dir / f"audio_{int(offset):08d}.flac"
        command = [
            ffmpeg, "-y", "-ss", str(offset), "-i", str(video_path),
            "-t", str(chunk_seconds), "-vn", "-ac", "1", "-ar", "16000",
            "-c:a", "flac", str(audio_path)
        ]
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=240)
        if result.returncode:
            audio_path.unlink(missing_ok=True)
            if offset == 0:
                raise HTTPException(status_code=400, detail="Could not extract audio from this video.")
            break
        if not audio_path.exists() or audio_path.stat().st_size == 0:
            audio_path.unlink(missing_ok=True)
            break

        try:
            with audio_path.open("rb") as audio_file:
                transcript = client.audio.transcriptions.create(
                    file=(audio_path.name, audio_file),
                    model="whisper-large-v3-turbo",
                    response_format="verbose_json",
                    timestamp_granularities=["segment"],
                    temperature=0
                )
            for segment in (getattr(transcript, "segments", None) or []):
                text = str(getattr(segment, "text", "")).strip()
                start = float(getattr(segment, "start", 0)) + offset
                end = float(getattr(segment, "end", start - offset)) + offset
                if text and end > start:
                    entries.append((start, end, text))
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"AI transcription failed: {str(exc)[:240]}") from exc
        finally:
            audio_path.unlink(missing_ok=True)

        if audio_path.stat().st_size if audio_path.exists() else False:
            pass
        offset += chunk_seconds
        # A short final chunk is followed by an empty extraction on the next pass.

    if not entries:
        raise HTTPException(status_code=422, detail="No spoken words were detected in this video.")

    with output_srt.open("w", encoding="utf-8-sig") as handle:
        for index, (start, end, text) in enumerate(entries, 1):
            safe_text = text.replace("-->", "→").replace("\\n", " ").strip()
            handle.write(f"{index}\n{_stamp(start)} --> {_stamp(end)}\n{safe_text}\n\n")
    return len(entries)


def shutil_which(command: str) -> str | None:
    import shutil
    return shutil.which(command)
