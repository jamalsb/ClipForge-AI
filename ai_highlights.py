from __future__ import annotations

import json
import os

from fastapi import HTTPException


def select_highlights(
    transcript_segments: list[tuple[float, float, str]],
    duration: float,
    clip_seconds: int,
    max_clips: int = 10,
) -> list[dict]:
    """Rank transcript moments and return safe, fixed-length clip windows."""
    api_key = os.getenv("GROQ_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="AI highlights need the GROQ_API_KEY server secret.")

    try:
        from groq import Groq
    except ImportError as exc:
        raise HTTPException(status_code=503, detail="The AI model package is not installed.") from exc

    transcript = "\n".join(
        f"[{start:.1f}-{end:.1f}] {text}" for start, end, text in transcript_segments
    )
    if len(transcript) > 24000:
        transcript = transcript[:24000]

    if not transcript.strip():
        raise HTTPException(status_code=422, detail="There is no transcript to analyse for highlights.")

    model = os.getenv("GROQ_HIGHLIGHT_MODEL", "llama-3.3-70b-versatile")
    client = Groq(api_key=api_key, timeout=120.0, max_retries=2)
    prompt = (
        "You are an expert short-form video editor. Find the strongest self-contained moments "
        "in this timestamped transcript for short social video clips. Prioritize a compelling "
        "hook, surprising or useful information, emotion, a clear story beat, or a satisfying "
        "payoff. Do not invent events or quote text absent from the transcript. "
        f"Each selected clip must be exactly {clip_seconds} seconds long and start within "
        f"0 to {max(0, duration - clip_seconds):.1f} seconds. Return at most {max_clips} "
        "non-duplicate moments. Prefer moments with enough spoken context and avoid cutting "
        "mid-sentence when possible. Output only a JSON object with a 'clips' array. "
        "Each item must contain numeric 'start', a short 'title', and a short 'reason'. "
        "Transcript:\n" + transcript
    )

    try:
        response = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": "Select evidence-based short video highlights. Return valid JSON only."},
                {"role": "user", "content": prompt},
            ],
            temperature=0.2,
            response_format={"type": "json_object"},
        )
        payload = json.loads(response.choices[0].message.content or "{}")
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"AI highlight analysis failed: {str(exc)[:240]}") from exc

    raw_clips = payload.get("clips", []) if isinstance(payload, dict) else []
    if not isinstance(raw_clips, list):
        raise HTTPException(status_code=502, detail="AI highlight response had an invalid format.")

    valid: list[dict] = []
    max_start = max(0.0, duration - clip_seconds)
    for item in raw_clips:
        if not isinstance(item, dict):
            continue
        try:
            start = min(max(float(item.get("start", 0)), 0.0), max_start)
        except (TypeError, ValueError):
            continue
        title = str(item.get("title", "Highlight")).strip()[:90] or "Highlight"
        reason = str(item.get("reason", "Selected from the spoken content.")).strip()[:220]
        valid.append({
            "start": round(start, 2),
            "end": round(start + clip_seconds, 2),
            "title": title,
            "reason": reason,
        })

    valid.sort(key=lambda clip: clip["start"])
    selected: list[dict] = []
    for clip in valid:
        if all(
            clip["end"] <= chosen["start"] or clip["start"] >= chosen["end"]
            for chosen in selected
        ):
            selected.append(clip)
        if len(selected) >= max_clips:
            break

    if not selected:
        raise HTTPException(status_code=422, detail="AI could not find suitable highlight moments in this video.")
    return selected
