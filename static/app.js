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
  statusBox.textContent = "Processing locally with FFmpeg. Longer videos may take a while.";
  resultBox.hidden = true;
  try {
    const response = await fetch("/api/process", { method: "POST", body: new FormData(form) });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.detail || "Processing failed.");
    const link = document.createElement("a");
    link.href = payload.download_url;
    link.textContent = "Download clips ZIP";
    const title = document.createElement("strong");
    title.textContent = "✓ " + payload.message;
    const info = document.createElement("p");
    info.textContent = payload.duration_seconds + " seconds source · Job " + payload.job_id;
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
