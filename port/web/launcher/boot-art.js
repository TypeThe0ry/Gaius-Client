// Gaius boot screen art: the vanilla loading-screen look (flat white logo over a dark screen, the
// outlined bar, white text with a hard shadow) drawn with Gaius' own pixel glyphs; no game asset,
// texture or font. Injected by port/scripts/postprocess-index-html.py (apply_gaius_boot_art). It
// only paints the boot overlay and reports what the launcher already measures: the stage and
// percentage (window.__gaiusSetBootProgress) and the bytes loaded so far (window.__gaiusLoadBytes,
// filled by gaius-boot.js and the portable decoder, plus the page's finished resource loads). The
// boot flow itself is unchanged, nothing here waits, and it moves only while there is progress.
(function installGaiusBootArt() {
  "use strict";
  const doc = document;
  const bootScreen = doc.getElementById("boot-screen");
  const bootBrand = doc.getElementById("boot-brand");
  const progressText = doc.getElementById("boot-progress-text");
  const bootProgress = doc.getElementById("boot-progress");
  const bootStack = doc.getElementById("gaius-boot-stack");
  if (!bootScreen || !bootBrand) return;

  // 5x7 bitmap glyphs (rows top to bottom, "#" = pixel). Uppercase, digits and punctuation.
  const GLYPHS = {
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "B": ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "G": [".####", "#....", "#....", "#.###", "#...#", "#...#", ".####"],
    "H": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "J": ["....#", "....#", "....#", "....#", "#...#", "#...#", ".###."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "M": ["#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "Q": [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "V": ["#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."],
    "W": ["#...#", "#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#"],
    "X": ["#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    "Z": ["#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"],
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": [".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."],
    " ": ["...", "...", "...", "...", "...", "...", "..."],
    ".": [".", ".", ".", ".", ".", ".", "#"],
    ",": ["..", "..", "..", "..", "..", ".#", "#."],
    ":": [".", "#", ".", ".", ".", "#", "."],
    "!": ["#", "#", "#", "#", "#", ".", "#"],
    "?": [".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."],
    "'": ["#", "#", ".", ".", ".", ".", "."],
    "-": ["....", "....", "....", "####", "....", "....", "...."],
    "+": [".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."],
    "/": ["....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."],
    "%": ["##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"],
    "(": [".#", "#.", "#.", "#.", "#.", "#.", ".#"],
    ")": ["#.", ".#", ".#", ".#", ".#", ".#", "#."],
    "_": ["....", "....", "....", "....", "....", "....", "####"],
  };

  function glyph(ch) {
    return GLYPHS[ch] || GLYPHS[ch.toUpperCase()] || GLYPHS["?"];
  }

  function textWidth(text) {
    let width = 0;
    for (const ch of String(text)) width += glyph(ch)[0].length + 1;
    return Math.max(0, width - 1);
  }

  // Draws text with a Minecraft-style drop shadow; returns the drawn width in pixels.
  function drawText(ctx, text, x, y, scale, color, shadow) {
    let cx = x;
    for (const ch of String(text)) {
      const rows = glyph(ch);
      for (let row = 0; row < rows.length; row++) {
        for (let col = 0; col < rows[row].length; col++) {
          if (rows[row][col] !== "#") continue;
          if (shadow) {
            ctx.fillStyle = shadow;
            ctx.fillRect(cx + (col + 1) * scale, y + (row + 1) * scale, scale, scale);
          }
          ctx.fillStyle = color;
          ctx.fillRect(cx + col * scale, y + row * scale, scale, scale);
        }
      }
      cx += (rows[0].length + 1) * scale;
    }
    return cx - x - scale;
  }

  // Small deterministic PRNG so the texture is stable between frames and reloads.
  function rng(seed) {
    let s = seed >>> 0 || 1;
    return function next() {
      s ^= s << 13; s >>>= 0;
      s ^= s >>> 17;
      s ^= s << 5; s >>>= 0;
      return s / 4294967296;
    };
  }

  // A darkened 16x16 dirt tile, the classic Minecraft menu background, generated per pixel.
  function dirtTile() {
    const tile = doc.createElement("canvas");
    tile.width = 16;
    tile.height = 16;
    const ctx = tile.getContext("2d");
    const random = rng(20261002);
    const palette = ["#4b3523", "#563d29", "#5f4430", "#3f2c1d", "#6b4e37", "#47331f", "#7a5b41"];
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const r = random();
        ctx.fillStyle = palette[Math.min(palette.length - 1, Math.floor(r * r * palette.length * 1.15))];
        ctx.fillRect(x, y, 1, 1);
      }
    }
    // Scatter a few small pebbles like the vanilla dirt texture.
    for (let i = 0; i < 9; i++) {
      const x = Math.floor(random() * 15);
      const y = Math.floor(random() * 15);
      ctx.fillStyle = random() < 0.5 ? "#8a8a84" : "#6e6e68";
      ctx.fillRect(x, y, 1, 1);
      if (random() < 0.5) ctx.fillRect(x + 1, y, 1, 1);
    }
    ctx.fillStyle = "rgba(0, 0, 0, 0.62)";
    ctx.fillRect(0, 0, 16, 16);
    return tile.toDataURL("image/png");
  }

  // Paint a fixed vanilla cubemap view while the Wasm client is compiling. The real title screen
  // still owns the animated panorama once it is ready; this canvas only removes the blank shell
  // interval and is deliberately inert when a build has no cached vanilla faces.
  function drawBootPanorama(canvas, faces) {
    if (!Array.isArray(faces) || faces.length !== 6 || !canvas) return;
    const width = 640;
    const height = 360;
    const faceSize = 128;
    const images = faces.map(source => {
      const image = new Image();
      image.decoding = "async";
      image.src = source;
      return image;
    });
    let loaded = 0;
    const paint = () => {
      if (loaded !== images.length) return;
      const facePixels = [];
      for (const image of images) {
        const face = doc.createElement("canvas");
        face.width = faceSize;
        face.height = faceSize;
        const faceContext = face.getContext("2d", {willReadFrequently: true});
        faceContext.drawImage(image, 0, 0, faceSize, faceSize);
        facePixels.push(faceContext.getImageData(0, 0, faceSize, faceSize).data);
      }
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext("2d");
      const output = context.createImageData(width, height);
      const field = Math.tan(Math.PI / 8);
      const aspect = width / height;
      for (let y = 0; y < height; y++) {
        const v = (1 - 2 * (y + 0.5) / height) * field;
        for (let x = 0; x < width; x++) {
          const u = (2 * (x + 0.5) / width - 1) * aspect * field;
          const length = Math.hypot(u, v, 1);
          const dx = u / length;
          const dy = v / length;
          const dz = -1 / length;
          const ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
          let face = 0, sc = 0, tc = 0, major = ax;
          if (ay > major) major = ay;
          if (az > major) major = az;
          if (major === ax) {
            face = dx > 0 ? 0 : 1;
            sc = dx > 0 ? -dz : dz;
            tc = -dy;
          } else if (major === ay) {
            face = dy > 0 ? 2 : 3;
            sc = dx;
            tc = dy > 0 ? dz : -dz;
          } else {
            face = dz > 0 ? 4 : 5;
            sc = dz > 0 ? dx : -dx;
            tc = -dy;
          }
          const sx = Math.max(0, Math.min(faceSize - 1, Math.floor((sc * 0.5 + 0.5) * faceSize)));
          const sy = Math.max(0, Math.min(faceSize - 1, Math.floor((tc * 0.5 + 0.5) * faceSize)));
          const source = (sy * faceSize + sx) * 4;
          const target = (y * width + x) * 4;
          output.data[target] = facePixels[face][source];
          output.data[target + 1] = facePixels[face][source + 1];
          output.data[target + 2] = facePixels[face][source + 2];
          output.data[target + 3] = 255;
        }
      }
      context.putImageData(output, 0, 0);
      canvas.hidden = false;
      canvas.dataset.ready = "1";
    };
    for (const image of images) {
      const ready = () => { loaded++; paint(); };
      image.addEventListener("load", ready, {once: true});
      image.addEventListener("error", () => {}, {once: true});
      if (image.complete && image.naturalWidth > 0) ready();
    }
  }

  // The flat blocky wordmark, 4 canvas pixels per cell (CSS scales it, pixelated).
  const LOGO = {
    "G": [".#####", "##....", "##....", "##.###", "##...#", "##...#", ".#####"],
    "A": [".####.", "##..##", "##..##", "######", "##..##", "##..##", "##..##"],
    "I": ["####", ".##.", ".##.", ".##.", ".##.", ".##.", "####"],
    "U": ["##..##", "##..##", "##..##", "##..##", "##..##", "##..##", ".####."],
    "S": [".#####", "##....", "##....", ".####.", "....##", "....##", "#####."],
  };

  function drawLogo(canvas, word) {
    const cell = 4;
    let cols = 0;
    for (const ch of word) cols += LOGO[ch][0].length + 1;
    cols -= 1;
    canvas.width = cols * cell;
    canvas.height = 7 * cell;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#ffffff";
    let ox = 0;
    for (const ch of word) {
      const rows = LOGO[ch];
      for (let r = 0; r < rows.length; r++) {
        for (let c = 0; c < rows[r].length; c++) {
          if (rows[r][c] === "#") ctx.fillRect((ox + c) * cell, r * cell, cell, cell);
        }
      }
      ox += rows[0].length + 1;
    }
  }

  const SHADOW = "#3f3f3f";
  // One glyph pixel is `size` CSS pixels, drawn at whole device pixels so it stays sharp.
  function paintLine(canvas, text, color, size) {
    if (/[\u0080-\uFFFF]/.test(String(text))) {
      canvas.style.display = "none";
      canvas.dataset.text = "";
      return;
    }
    canvas.style.display = "";
    const cssScale = size || 2;
    const density = Math.max(1, Math.min(3, Math.round(window.devicePixelRatio || 1)));
    const s = cssScale * density;
    canvas.width = Math.max(1, textWidth(text) * s + s);
    canvas.height = 7 * s + s;
    canvas.style.width = canvas.width / density + "px";
    canvas.style.height = canvas.height / density + "px";
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (text) drawText(ctx, text, 0, 0, s, color, SHADOW);
    canvas.dataset.empty = text ? "0" : "1";
  }

  function canvas(id, parent) {
    const element = doc.createElement("canvas");
    element.id = id;
    element.setAttribute("aria-hidden", "true");
    parent.appendChild(element);
    return element;
  }

  const art = doc.createElement("div");
  art.id = "gaius-boot-art";
  art.setAttribute("aria-hidden", "true");
  drawLogo(canvas("gaius-boot-logo", art), "GAIUS");
  const translate = (key, fallback) => typeof window.__gaiusTranslate === "function"
    ? window.__gaiusTranslate(key) : fallback;
  paintLine(canvas("gaius-boot-edition", art), translate("browser", "BROWSER CLIENT"), "#a0a0a0");
  bootBrand.insertAdjacentElement("afterend", art);

  const lines = doc.createElement("div");
  lines.id = "gaius-boot-lines";
  lines.setAttribute("aria-hidden", "true");
  const label = canvas("gaius-boot-label", lines);
  const bytesLine = canvas("gaius-boot-bytes", lines);
  const hint = canvas("gaius-boot-hint", lines);
  (bootProgress || progressText || bootBrand).insertAdjacentElement("afterend", lines);

  const errorTitle = doc.createElement("div");
  errorTitle.id = "gaius-boot-error-title";
  errorTitle.setAttribute("aria-hidden", "true");
  paintLine(canvas("gaius-boot-error-heading", errorTitle), translate("couldNotStart", "GAIUS COULD NOT START"), "#ffffff",
    window.innerWidth < 600 ? 2 : 3);
  (bootStack || lines).insertAdjacentElement("beforebegin", errorTitle);
  const root = doc.documentElement;
  root.style.setProperty("--gaius-boot-dirt", "url(" + dirtTile() + ")");
  root.dataset.gaiusBootArt = "1";
  const panorama = doc.createElement("canvas");
  panorama.id = "gaius-boot-panorama";
  panorama.hidden = true;
  panorama.setAttribute("aria-hidden", "true");
  bootScreen.appendChild(panorama);
  drawBootPanorama(panorama, window.__gaiusBootPanoramaFaces);

  doc.documentElement.dataset.gaiusBootArt = "1";

  // Bytes: what gaius-boot.js and the portable decoder count while a large payload streams
  // (window.__gaiusLoadBytes[key] = {loaded, total}), and each finished resource load of the page
  // (Resource Timing, recorded by the browser anyway). A finished load replaces the streaming
  // count of the same URL.
  const loadBytes = window.__gaiusLoadBytes || (window.__gaiusLoadBytes = {});
  function recordResource(entry) {
    const size = Number(entry.encodedBodySize) || Number(entry.transferSize) || Number(entry.decodedBodySize) || 0;
    if (size > 0) loadBytes[entry.name] = {loaded: size, total: size};
  }
  try {
    if (typeof PerformanceObserver === "function") {
      new PerformanceObserver((list) => list.getEntries().forEach(recordResource))
        .observe({type: "resource", buffered: true});
    }
  } catch (error) {
    // Resource Timing is optional; the stage and percentage still show.
  }
  function megabytes(value) {
    return (value / 1e6).toFixed(value >= 1e8 ? 0 : 1);
  }
  function bytesText() {
    let loaded = 0;
    let total = 0;
    let streaming = false;
    for (const key of Object.keys(loadBytes)) {
      const entry = loadBytes[key];
      const done = Math.max(0, Number(entry && entry.loaded) || 0);
      const size = Math.max(done, Number(entry && entry.total) || 0);
      loaded += done;
      total += size;
      if (size > done) streaming = true;
    }
    if (loaded < 1e5) return "";
    return streaming ? megabytes(loaded) + " / " + megabytes(total) + " MB" : megabytes(loaded) + " MB LOADED";
  }

  // Progress after the client's main() returns. The launcher holds 82% until the first usable
  // screen, so this paints two calibrated phases on top of it: starting Minecraft (82-90%) and
  // the resource reload (90-99%). Each phase eases toward its cap over the duration the previous
  // boot measured (window.localStorage, per browser), and the reload phase also follows
  // Minecraft's own reload progress (window.__gaiusReloadProgress, published by
  // dev.gaius.browser.BrowserBootProgress), whichever is further along.
  const timingKey = "gaius.bootTimings";
  function readTimings() {
    try {
      const value = JSON.parse(localStorage.getItem(timingKey) || "{}");
      return value && typeof value === "object" ? value : {};
    } catch (error) {
      return {};
    }
  }
  const learned = readTimings();
  const estimate = {
    start: Math.min(60000, Math.max(3000, Number(learned.start) || 12000)),
    reload: Math.min(90000, Math.max(3000, Number(learned.reload) || 20000)),
  };
  let phase = "";
  let phaseStartedAt = 0;
  const measured = {};
  function ease(elapsed, expected) {
    return 1 - Math.exp(-2.2 * Math.max(0, elapsed) / expected);
  }
  function enterPhase(next, now) {
    if (phase && phaseStartedAt) measured[phase] = now - phaseStartedAt;
    phase = next;
    phaseStartedAt = now;
  }
  function rememberTimings() {
    try {
      const merged = Object.assign({}, learned);
      for (const key of Object.keys(measured)) {
        if (measured[key] > 500) merged[key] = Math.round(measured[key]);
      }
      localStorage.setItem(timingKey, JSON.stringify(merged));
    } catch (error) {
      // Calibration is optional.
    }
  }

  // Four updates a second: enough for the bar's own 250 ms width transition to read as steady
  // progress, and far cheaper than a per-frame loop while the page is busy loading.
  const TICK_MS = 250;
  let lastText = "";
  let lastBytes = "";
  let lastValue = -1;
  let lastChange = performance.now();
  let timer = 0;
  function tick() {
    timer = 0;
    const now = performance.now();
    const pending = window.__gaiusInitialBootPending === true;
    const visible = !bootScreen.hidden;
    const setProgress = window.__gaiusSetBootProgress;
    if (pending && visible && typeof setProgress === "function") {
      const state = window.__gaiusMinecraftState || {};
      const reloading = String(state.overlay || "").endsWith("LoadingOverlay");
      const started = typeof window.__gaiusBootTimings === "object"
        && window.__gaiusBootTimings && Number(window.__gaiusBootTimings.mainReturned) > 0;
      if (reloading && phase !== "reload") enterPhase("reload", now);
      else if (!reloading && started && !phase) enterPhase("start", now);
      if (phase === "start") {
        setProgress(82 + 8 * ease(now - phaseStartedAt, estimate.start), translate("startingGame", "Starting Minecraft..."));
      } else if (phase === "reload") {
        const timed = ease(now - phaseStartedAt, estimate.reload);
        const actual = Number(window.__gaiusReloadProgress);
        // Minecraft's own figure reaches its last stage early; trust it only half-way.
        const reported = Number.isFinite(actual) ? Math.min(1, actual) * 0.5 : 0;
        setProgress(90 + 9 * Math.max(timed, reported), translate("loadingResources", "Loading game resources..."));
      }
    } else if (phase && !pending) {
      enterPhase("done", now);
      rememberTimings();
      phase = "";
    }
    const text = progressText ? String(progressText.textContent || "") : "";
    if (text !== lastText) {
      lastText = text;
      const match = /^(\d+)%\s*(.*)$/.exec(text);
      const value = match ? Number(match[1]) : -1;
      if (value !== lastValue) {
        lastValue = value;
        lastChange = now;
      }
      const stage = (match ? match[2] : text).replace(/\.\.\.$/, "");
      paintLine(label, match ? stage + "  " + match[1] + "%" : stage, "#ffffff");
    }
    const bytes = bytesText();
    if (bytes !== lastBytes) {
      lastBytes = bytes;
      lastChange = now;
      paintLine(bytesLine, bytes, "#a0a0a0");
    }
    const stalled = visible && pending && now - lastChange > 20000;
    const hintText = stalled ? translate("stalled", "Still working - large worlds and slow disks can take a while") : "";
    if (hint.dataset.text !== hintText) {
      hint.dataset.text = hintText;
      paintLine(hint, hintText, "#a0a0a0");
    }
    if (!bootScreen.hidden || pending) timer = setTimeout(tick, TICK_MS);
  }
  tick();
  // The launcher can show the boot overlay again (startup retry); resume painting then.
  new MutationObserver(() => {
    if (!timer && !bootScreen.hidden) tick();
  }).observe(bootScreen, {attributes: true, attributeFilter: ["hidden"]});
})();
