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
  statusBox.className = "status";
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!input.files[0]) {
    statusBox.textContent = "Please choose a video first.";
    statusBox.className = "status error";
    return;
  }

  button.disabled = true;
  button.classList.add("is-loading");
  button.setAttribute("aria-busy", "true");
  button.innerHTML = '<span class="button-spinner" aria-hidden="true"></span> Generating clips…';
  statusBox.className = "status processing";
  const wantsSubtitles = document.getElementById("subtitle_mode").value === "auto";
  const wantsHighlights = document.getElementById("highlight_mode").value === "auto";
  statusBox.textContent = wantsHighlights
    ? "AI is reviewing the transcript and selecting moments. Your clips will appear here when processing is complete."
    : (wantsSubtitles
      ? "Creating your clips and preparing timestamped subtitles. Please keep this page open."
      : "Your video is being processed. Your download will appear here when all clips are ready.");
  resultBox.hidden = true;

  try {
    const response = await fetch("/api/process", { method: "POST", body: new FormData(form) });
    let payload;
    try {
      payload = await response.json();
    } catch {
      throw new Error("The server returned an unreadable response. Please try again.");
    }
    if (!response.ok) throw new Error(payload.detail || "Processing failed.");
    if (!payload.download_url || !Number.isFinite(Number(payload.clip_count))) {
      throw new Error("The server did not confirm that clips were generated. Please try again.");
    }

    const link = document.createElement("a");
    link.href = payload.download_url;
    link.textContent = "Download clips ZIP";
    const title = document.createElement("strong");
    title.textContent = "✓ " + (payload.message || "Your clips are ready.");
    const info = document.createElement("p");
    info.textContent = payload.duration_seconds + " seconds source · Job " + payload.job_id +
      (payload.subtitle_count ? " · " + payload.subtitle_count + " subtitle lines included" : "") +
      (payload.highlight_count ? " · " + payload.highlight_count + " AI highlights selected" : "");
    resultBox.replaceChildren(title, info, link);
    resultBox.hidden = false;
    statusBox.className = "status success";
    statusBox.textContent = "Your clips are ready to download.";
  } catch (error) {
    statusBox.className = "status error";
    statusBox.textContent = error.message || "Could not process this video.";
  } finally {
    button.disabled = false;
    button.classList.remove("is-loading");
    button.removeAttribute("aria-busy");
    button.innerHTML = 'Create my clips <span class="button-arrow">→</span>';
  }
});
