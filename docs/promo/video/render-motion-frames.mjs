import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));

function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const chromePath = argument("chrome", "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
const htmlPath = path.resolve(argument("html", path.join(scriptDirectory, "player-guide-motion.html")));
const framesPath = path.resolve(argument("frames", path.join(scriptDirectory, "motion-frames")));
const fps = Number(argument("fps", "15"));
const width = Number(argument("width", "1080"));
const height = Number(argument("height", "1920"));
const requestedStartFrame = Number(argument("start-frame", "0"));
const requestedEndFrame = Number(argument("end-frame", "-1"));

if (!existsSync(chromePath)) throw new Error(`Chrome not found: ${chromePath}`);
if (!existsSync(htmlPath)) throw new Error(`Motion page not found: ${htmlPath}`);
if (!Number.isFinite(fps) || fps < 1) throw new Error(`Invalid frame rate: ${fps}`);

await mkdir(framesPath, { recursive: true });

const port = 9310 + process.pid % 300;
const profilePath = path.join(tmpdir(), `maimai-q-video-chrome-${process.pid}`);
const pageUrl = pathToFileURL(htmlPath).href;
const chrome = spawn(chromePath, [
  "--headless=new",
  "--disable-gpu",
  "--hide-scrollbars",
  "--no-first-run",
  "--no-default-browser-check",
  "--remote-allow-origins=*",
  `--remote-debugging-port=${port}`,
  `--user-data-dir=${profilePath}`,
  `--window-size=${width},${height}`,
  "--force-device-scale-factor=1",
  pageUrl
], {
  stdio: ["ignore", "ignore", "pipe"],
  windowsHide: true
});

let chromeError = "";
chrome.stderr.on("data", chunk => {
  chromeError += chunk.toString();
  if (chromeError.length > 12000) chromeError = chromeError.slice(-12000);
});

async function waitForPage() {
  const endpoint = `http://127.0.0.1:${port}/json/list`;
  for (let attempt = 0; attempt < 120; attempt++) {
    try {
      const pages = await fetch(endpoint).then(response => response.json());
      const page = pages.find(item => item.type === "page" && item.url.startsWith("file:"));
      if (page?.webSocketDebuggerUrl) return page;
    } catch {
      // Chrome may need a moment to open its debugging endpoint.
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Chrome debugging page did not become ready.\n${chromeError}`);
}

const page = await waitForPage();
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});

let requestId = 0;
const pending = new Map();
socket.addEventListener("message", event => {
  const message = JSON.parse(event.data);
  if (!message.id || !pending.has(message.id)) return;
  const { resolve, reject } = pending.get(message.id);
  pending.delete(message.id);
  if (message.error) reject(new Error(`${message.error.message}: ${JSON.stringify(message.error.data ?? {})}`));
  else resolve(message.result ?? {});
});

function call(method, params = {}) {
  const id = ++requestId;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function evaluate(expression, awaitPromise = false) {
  const result = await call("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  }
  return result.result?.value;
}

try {
  await call("Page.enable");
  await call("Runtime.enable");
  await call("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: false,
    screenWidth: width,
    screenHeight: height
  });
  await evaluate("document.fonts.ready.then(() => true)", true);
  const duration = Number(await evaluate("window.videoDuration"));
  if (!Number.isFinite(duration) || duration <= 0) throw new Error("The motion page did not expose a valid video duration.");

  const frameCount = Math.ceil(duration * fps);
  const startFrame = Math.max(0, Math.min(frameCount, Math.floor(requestedStartFrame)));
  const endFrame = requestedEndFrame < 0
    ? frameCount
    : Math.max(startFrame, Math.min(frameCount, Math.ceil(requestedEndFrame)));
  console.log(`Rendering frames ${startFrame}-${endFrame - 1} of ${frameCount} at ${fps} fps (${duration.toFixed(1)} seconds).`);
  for (let frame = startFrame; frame < endFrame; frame++) {
    const seconds = frame / fps;
    await evaluate(`window.renderFrame(${seconds.toFixed(6)})`);
    await evaluate("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))", true);
    const screenshot = await call("Page.captureScreenshot", {
      format: "jpeg",
      quality: 92,
      fromSurface: true,
      captureBeyondViewport: false
    });
    const frameName = `frame-${String(frame).padStart(6, "0")}.jpg`;
    await writeFile(path.join(framesPath, frameName), Buffer.from(screenshot.data, "base64"));
    if ((frame - startFrame) % 75 === 0 || frame === endFrame - 1) {
      console.log(`Rendered ${frame - startFrame + 1}/${endFrame - startFrame} selected frames`);
    }
  }

  await writeFile(
    path.join(framesPath, "render.json"),
    `${JSON.stringify({ fps, duration, frameCount, width, height }, null, 2)}\n`,
    "utf8"
  );
} finally {
  socket.close();
  chrome.kill();
}
