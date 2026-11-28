const form = document.getElementById("clip-form");
const input = document.getElementById("video");
const fileTitle = document.getElementById("file-title");
const fileMeta = document.getElementById("file-meta");
const button = document.getElementById("submit-btn");
const statusBox = document.getElementById("status");
const resultBox = document.getElementById("result");

input.addEventListener("change", () => {
  const file = input.files[0];
  if (!file) return;
  fileTitle.textContent = file.name;
  fileMeta.textContent = (file.size / (1024 * 1024)).toFixed(1) + " MB · Ready to process";
  resultBox.hidden = true;
  statusBox.textContent = "";
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!input.files[0]) {
    statusBox.textContent = "Please choose a video first.";
    statusBox.className = "status error";
    return;
  }
  button.disabled = true;
  button.textContent = "Creating clips… please wait";
  statusBox.className = "status";
  const wantsSubtitles = document.getElementById("subtitle_mode").value === "auto";
  const wantsHighlights = document.getElementById("highlight_mode").value === "auto";
  statusBox.textContent = wantsHighlights
    ? "AI is analysing the spoken content to find the strongest moments. This may take a while."
    : (wantsSubtitles ? "Creating clips and generating AI subtitles. This may take a while." : "Creating clips on the processing server. Longer videos may take a while.");
  resultBox.hidden = true;
  try {
    const response = await fetch("/api/process", { method: "POST", body: new FormData(form) });
    // Read as text first: a gateway timeout or crash returns HTML, not JSON.
    const raw = await response.text();
    let payload = null;
    try { payload = JSON.parse(raw); } catch (parseError) { payload = null; }
    if (!payload) {
      const hint = [502, 503, 504].includes(response.status)
        ? "The server timed out or restarted while processing (common on the free Render plan with long videos). Try a shorter video or lower resolution."
        : (response.status === 413 ? "The video is too large for the server." : "The server sent an unreadable reply.");
      throw new Error(hint + " (HTTP " + response.status + ")");
    }
    if (!response.ok) {
      const detail = typeof payload.detail === "string" ? payload.detail : JSON.stringify(payload.detail || "");
      throw new Error(detail || "Processing failed.");
    }
    const link = document.createElement("a");
    link.href = payload.download_url;
    link.textContent = "Download clips ZIP";
    const title = document.createElement("strong");
    title.textContent = "✓ " + payload.message;
    const info = document.createElement("p");
    info.textContent = payload.duration_seconds + " seconds source · Job " + payload.job_id + (payload.subtitle_count ? " · " + payload.subtitle_count + " subtitle lines included" : "") + (payload.highlight_count ? " · " + payload.highlight_count + " AI highlights selected" : "");
    resultBox.replaceChildren(title, info, link);
    resultBox.hidden = false;
    statusBox.textContent = "Your clips are ready.";
  } catch (error) {
    statusBox.className = "status error";
    statusBox.textContent = error.message || "Could not process this video.";
  } finally {
    button.disabled = false;
    button.innerHTML = 'Create my clips <span>→</span>';
  }
});
