from __future__ import annotations

import json
import os
import shutil
import subprocess
import uuid
import zipfile
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from ai_subtitles import transcribe_video
from ai_highlights import select_highlights

ROOT = Path(__file__).resolve().parent
WORK = ROOT / "work"
UPLOADS = WORK / "uploads"
OUTPUTS = WORK / "outputs"
for directory in (UPLOADS, OUTPUTS):
    directory.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="ClipForge AI", version="0.3.0")
app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")


def executable(name: str) -> str:
    found = shutil.which(name)
    if not found:
        raise HTTPException(status_code=500, detail=f"{name} was not found. Install FFmpeg and add its bin folder to PATH.")
    return found


def video_duration(path: Path) -> float:
    result = subprocess.run(
        [executable("ffprobe"), "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, check=False, timeout=60
    )
    try:
        duration = float(result.stdout.strip())
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="Could not read this video's duration.")
    if duration <= 0:
        raise HTTPException(status_code=400, detail="The video has no valid duration.")
    return duration


@app.get("/")
def home():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/api/health")
def health():
    return {"ok": True, "ffmpeg_available": bool(shutil.which("ffmpeg")),
            "ffprobe_available": bool(shutil.which("ffprobe")),
            "ai_subtitles_enabled": bool(os.getenv("GROQ_API_KEY")),
            "ai_highlights_enabled": bool(os.getenv("GROQ_API_KEY"))}


@app.post("/api/process")
async def process_video(
    video: UploadFile = File(...),
    clip_seconds: int = Form(30),
    aspect: str = Form("9:16"),
    resolution: str = Form("1080"),
    subtitle_mode: str = Form("off"),
    highlight_mode: str = Form("off"),
):
    ffmpeg = executable("ffmpeg")
    if clip_seconds not in (15, 30, 45, 60):
        raise HTTPException(status_code=400, detail="Choose 15, 30, 45 or 60 seconds.")
    supported_aspects = ("9:16", "1:1", "16:9")
    if aspect != "all" and aspect not in supported_aspects:
        raise HTTPException(status_code=400, detail="Unsupported aspect ratio.")
    export_aspects = supported_aspects if aspect == "all" else (aspect,)
    if resolution not in ("720", "1080"):
        raise HTTPException(status_code=400, detail="Choose 720p or 1080p.")
    if subtitle_mode not in ("off", "auto"):
        raise HTTPException(status_code=400, detail="Unsupported subtitle option.")
    if highlight_mode not in ("off", "auto"):
        raise HTTPException(status_code=400, detail="Unsupported highlight option.")

    suffix = Path(video.filename or "input.mp4").suffix.lower()
    if suffix not in (".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v"):
        suffix = ".mp4"

    job_id = uuid.uuid4().hex[:10]
    source = UPLOADS / f"{job_id}{suffix}"
    job_dir = OUTPUTS / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    zip_path = OUTPUTS / f"ClipForge_{job_id}.zip"

    try:
        size = 0
        with source.open("wb") as target:
            while chunk := await video.read(1024 * 1024):
                size += len(chunk)
                if size > 2 * 1024 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="Video exceeds the 2 GB MVP upload limit.")
                target.write(chunk)

        duration = video_duration(source)
        if duration < clip_seconds:
            raise HTTPException(status_code=400, detail=f"Video is shorter than {clip_seconds} seconds.")

        dimensions = {
            "9:16": ((720, 1280) if resolution == "720" else (1080, 1920)),
            "1:1": (int(resolution), int(resolution)),
            "16:9": ((1280, 720) if resolution == "720" else (1920, 1080)),
        }

        subtitle_path = job_dir / "subtitles.srt" if subtitle_mode == "auto" else None
        subtitle_count = 0
        highlights = []
        transcript_segments = []
        if subtitle_mode == "auto" or highlight_mode == "auto":
            transcript_segments = transcribe_video(source, subtitle_path, job_dir, duration)
            subtitle_count = len(transcript_segments) if subtitle_path else 0

        if highlight_mode == "auto":
            highlights = select_highlights(transcript_segments, duration, clip_seconds)
            clip_specs = [(item["start"], clip_seconds) for item in highlights]
        else:
            clip_count = int(duration // clip_seconds)
            if clip_count > 200:
                raise HTTPException(status_code=400, detail="This would create over 200 clips. Choose a longer clip length.")
            clip_specs = [(index * clip_seconds, clip_seconds) for index in range(clip_count)]

        outputs = []
        for index, (start, segment_length) in enumerate(clip_specs):
            for output_aspect in export_aspects:
                width, height = dimensions[output_aspect]
                aspect_suffix = output_aspect.replace(":", "x")
                output = job_dir / f"clip_{index + 1:03d}_{aspect_suffix}.mp4"
                scale_pad = (
                    f"scale={width}:{height}:force_original_aspect_ratio=decrease,"
                    f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setsar=1"
                )
                command = [
                    ffmpeg, "-y", "-ss", str(start), "-i", str(source),
                    "-t", str(segment_length), "-vf", scale_pad,
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                    str(output)
                ]
                result = subprocess.run(command, capture_output=True, text=True, check=False)
                if result.returncode:
                    raise HTTPException(status_code=500, detail=f"FFmpeg failed on clip {index + 1} ({output_aspect}): {(result.stderr or '')[-900:]}")
                outputs.append(output)

        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for output in outputs:
                archive.write(output, arcname=output.name)
            if subtitle_path and subtitle_path.is_file():
                archive.write(subtitle_path, arcname="subtitles.srt")
            if highlights:
                archive.writestr("highlights.json", json.dumps(highlights, ensure_ascii=False, indent=2))

        return {"job_id": job_id, "clip_count": len(outputs),
                "duration_seconds": round(duration, 1),
                "download_url": f"/api/download/{zip_path.name}",
                "subtitle_count": subtitle_count,
                "highlight_count": len(highlights),
                "message": f"Created {len(outputs)} video files from {len(clip_specs)} source clips in {', '.join(export_aspects)} format(s)." + (" AI-selected highlights were used." if highlights else "") + (f" Included {subtitle_count} subtitle lines." if subtitle_count else "")}
    finally:
        await video.close()


@app.get("/api/download/{filename}")
def download(filename: str):
    if Path(filename).name != filename or not filename.endswith(".zip"):
        raise HTTPException(status_code=400, detail="Invalid download name.")
    path = OUTPUTS / filename
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Download not found.")
    return FileResponse(path, media_type="application/zip", filename=filename)
