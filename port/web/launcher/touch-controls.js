// Gaius touch controls: Bedrock-style on-screen controls for phones and tablets.
// Injected by port/scripts/postprocess-index-html.py (apply_gaius_touch_controls). The game is
// driven through window.__gaiusInput, which the GLFW (1.21.11, 26.2) and SDL (26.3) DOM bridges
// install: positions are CSS pixels relative to #mc-canvas, buttons use GLFW numbers (0 left,
// 1 right) and keys use KeyboardEvent.code names. While the controls are active,
// window.__gaiusTouchActive tells both bridges to skip real pointer lock (the camera follows
// look() deltas instead). Nothing is created until a touch screen is used, so desktop mouse and
// keyboard play is unchanged. ?touch=1 forces the controls on, ?touch=0 off and ?touch=auto
// restores detection; the choice is remembered in localStorage.
(function installGaiusTouchControls() {
  "use strict";
  const w = window;
  const doc = document;
  if (w.__gaiusTouch) return;

  const MODE_KEY = "gaius.touchControls";
  const SETTINGS_KEY = "gaius.touchSettings";
  const DEFAULTS = Object.freeze({opacity: 0.6, size: 1, sensitivity: 2});
  const LIMITS = Object.freeze({
    opacity: [0.15, 1],
    size: [0.6, 1.6],
    sensitivity: [0.3, 6]
  });
  const TAP_MS = 250;            // world: shorter and still = use (right click)
  const BREAK_HOLD_MS = 300;     // world: held still this long = attack (left button)
  const LONG_PRESS_MS = 400;     // GUI: held still this long = right click
  const SNEAK_HOLD_MS = 350;     // sneak: longer than this = momentary instead of toggle
  const DROP_STACK_MS = 450;     // drop: held this long = drop the whole stack
  const DOUBLE_TAP_MS = 300;     // joystick: forward again within this = sprint
  const MIN_HOLD_MS = 80;        // longer than one 50 ms tick, so KeyMapping.isDown() sees it
  const KEYBOARD_GRACE_MS = 700; // the game needs a few ticks to focus a text field
  const KEYBOARD_MIN_PX = 60;    // visual viewport covered by at least this much = keyboard shown
  const DROP_SPRINT_GAP_MS = 60; // single drop: Ctrl stays up this long after Q's minimum hold
  const COMPAT_MOUSE_MS = 700;   // mouse events this soon after a touch were synthesised from it
  const SLOP_PX = 10;
  const WHEEL_STEP_PX = 28;
  const STICK_DEADZONE = 0.22;
  const STICK_AXIS = 0.3827;     // sin(22.5 deg): eight 45 degree sectors
  const SPRINT_EDGE = 0.96;
  const HOTBAR_MIN_TOUCH_PX = 40;
  const SENTINEL = "\u200b\u200b";

  const now = () => (typeof performance !== "undefined" && performance.now) ? performance.now() : Date.now();
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const storage = {
    get(key) {
      try {
        return w.localStorage ? w.localStorage.getItem(key) : null;
      } catch (error) {
        return null;
      }
    },
    set(key, value) {
      try {
        if (!w.localStorage) return;
        if (value == null) w.localStorage.removeItem(key);
        else w.localStorage.setItem(key, value);
      } catch (error) {
        // Storage is best effort (private browsing, blocked site data).
      }
    }
  };

  // ---- activation

  function readOverride() {
    let value = null;
    try {
      value = new URLSearchParams(w.location.search).get("touch");
    } catch (error) {
      value = null;
    }
    if (value === "1" || value === "0") {
      storage.set(MODE_KEY, value);
      return value;
    }
    if (value === "auto") {
      storage.set(MODE_KEY, null);
      return null;
    }
    const stored = storage.get(MODE_KEY);
    return stored === "1" || stored === "0" ? stored : null;
  }

  function mediaMatches(query) {
    try {
      return !!(w.matchMedia && w.matchMedia(query).matches);
    } catch (error) {
      return false;
    }
  }

  // Touch-primary: the primary pointer is coarse and the device reports touch points. A laptop
  // with a touch screen has a fine primary pointer and waits for its first real touch instead.
  function touchPrimary() {
    const points = Number(w.navigator && w.navigator.maxTouchPoints) || 0;
    return points > 0 && mediaMatches("(pointer: coarse)");
  }

  function loadSettings() {
    const settings = {opacity: DEFAULTS.opacity, size: DEFAULTS.size, sensitivity: DEFAULTS.sensitivity};
    let parsed = null;
    try {
      parsed = JSON.parse(storage.get(SETTINGS_KEY) || "null");
    } catch (error) {
      parsed = null;
    }
    if (parsed && typeof parsed === "object") {
      Object.keys(LIMITS).forEach(name => {
        const number = Number(parsed[name]);
        if (Number.isFinite(number)) settings[name] = clamp(number, LIMITS[name][0], LIMITS[name][1]);
      });
    }
    return settings;
  }

  // ---- pure geometry (exposed on window.__gaiusTouch.core for the smoke test)

  // Keys for a joystick displacement: four axes from eight 45 degree sectors, a dead zone in the
  // middle, and sprint when pushed to the rim (or latched by a double tap) while going forward.
  function stickKeys(dx, dy, radius, sprintLatched) {
    const distance = Math.hypot(dx, dy);
    const magnitude = radius > 0 ? Math.min(1, distance / radius) : 0;
    const keys = {forward: false, back: false, left: false, right: false, sprint: false, magnitude: magnitude};
    if (magnitude < STICK_DEADZONE || distance === 0) return keys;
    const nx = dx / distance;
    const ny = dy / distance;
    keys.forward = ny < -STICK_AXIS;
    keys.back = ny > STICK_AXIS;
    keys.left = nx < -STICK_AXIS;
    keys.right = nx > STICK_AXIS;
    keys.sprint = keys.forward && !keys.back && (magnitude >= SPRINT_EDGE || !!sprintLatched);
    return keys;
  }

  // Window.calculateScale: the largest scale up to the GUI Scale option (0 = auto) that keeps the
  // scaled framebuffer at least 320x240.
  function autoGuiScale(framebufferWidth, framebufferHeight, setting) {
    let scale = 1;
    while (scale !== setting && scale < framebufferWidth && scale < framebufferHeight &&
        Math.floor(framebufferWidth / (scale + 1)) >= 320 && Math.floor(framebufferHeight / (scale + 1)) >= 240) {
      scale++;
    }
    return scale;
  }

  // Screens report their GUI-scaled size; in the world the scale is inferred from the last screen
  // seen (an explicit GUI Scale setting stays fixed, auto follows the framebuffer).
  function guiSize(metrics, state, memory) {
    const size = state && state.screenSize;
    if (size && size.width > 0 && size.height > 0) {
      const scale = Math.max(1, Math.round(metrics.framebufferWidth / size.width));
      memory.scale = scale;
      memory.autoScale = autoGuiScale(metrics.framebufferWidth, metrics.framebufferHeight, 0);
      return {width: size.width, height: size.height, scale: scale};
    }
    const setting = memory.scale && memory.scale < memory.autoScale ? memory.scale : 0;
    const scale = autoGuiScale(metrics.framebufferWidth, metrics.framebufferHeight, setting);
    return {
      width: Math.ceil(metrics.framebufferWidth / scale),
      height: Math.ceil(metrics.framebufferHeight / scale),
      scale: scale
    };
  }

  // Client (viewport) coordinates to the CSS pixels the bridges use, through the canvas box.
  function toCanvas(metrics, clientX, clientY) {
    const rect = metrics.rect;
    return {
      x: (clientX - rect.left) * metrics.cssWidth / rect.width,
      y: (clientY - rect.top) * metrics.cssHeight / rect.height
    };
  }

  // MouseHandler: GUI position = window position * guiScaledSize / windowSize.
  function toGui(metrics, gui, clientX, clientY) {
    const point = toCanvas(metrics, clientX, clientY);
    return {x: point.x * gui.width / metrics.cssWidth, y: point.y * gui.height / metrics.cssHeight};
  }

  function guiToClient(metrics, gui, x, y) {
    const rect = metrics.rect;
    return {
      x: rect.left + x * metrics.cssWidth / gui.width * rect.width / metrics.cssWidth,
      y: rect.top + y * metrics.cssHeight / gui.height * rect.height / metrics.cssHeight
    };
  }

  // Gui.renderItemHotbar: 182x22 at (guiWidth / 2 - 91, guiHeight - 22), slots 20 apart.
  function hotbarRect(metrics, gui) {
    const left = Math.floor(gui.width / 2) - 91;
    const top = gui.height - 22;
    const a = guiToClient(metrics, gui, left, top);
    const b = guiToClient(metrics, gui, left + 182, gui.height);
    const height = b.y - a.y;
    const touchTop = Math.min(a.y, b.y - HOTBAR_MIN_TOUCH_PX);
    return {x: a.x, y: a.y, w: b.x - a.x, h: height, touchY: touchTop};
  }

  function hotbarSlotAt(metrics, gui, clientX, clientY) {
    const rect = hotbarRect(metrics, gui);
    if (clientY < rect.touchY || clientY > rect.y + rect.h || clientX < rect.x || clientX >= rect.x + rect.w) {
      return -1;
    }
    const point = toGui(metrics, gui, clientX, clientY);
    const left = Math.floor(gui.width / 2) - 91;
    return clamp(Math.floor((point.x - left - 1) / 20), 0, 8);
  }

  function computeLayout(width, height, size, safe, hotbar, fullscreen, guiLimit) {
    const unit = clamp(Math.min(width, height) * 0.15, 44, 104) * size;
    const gap = Math.round(unit * 0.28);
    const left = safe.left + gap;
    const right = width - safe.right - gap;
    const top = safe.top + Math.round(gap * 0.6);
    const bottom = height - safe.bottom - gap;
    const big = Math.round(unit);
    const small = Math.round(unit * 0.6);
    const box = (x, y, s) => ({x: Math.round(x), y: Math.round(y), w: s, h: s});
    const rects = {};
    // Bottom-right cluster: [attack][sneak] over [use][jump].
    rects.jump = box(right - big, bottom - big, big);
    rects.use = box(rects.jump.x - gap - big, rects.jump.y, big);
    rects.sneak = box(rects.jump.x, rects.jump.y - gap - big, big);
    rects.attack = box(rects.use.x, rects.sneak.y, big);
    const stickRadius = Math.round(unit * 1.05);
    const stick = {cx: left + stickRadius, cy: bottom - stickRadius, r: stickRadius};
    const row = (ids) => {
      let x = right;
      ids.forEach(id => {
        x -= small;
        rects[id] = box(x, top, small);
        x -= Math.round(gap * 0.6);
      });
    };
    const worldRow = ["pause", "chat", "perspective", "drop", "settings"];
    const guiRow = ["back", "keyboard", "settings"];
    if (fullscreen) {
      worldRow.push("fullscreen");
      guiRow.push("fullscreen");
    }
    row(worldRow);
    // GUI screens get a lighter row of their own.
    const guiRects = {};
    // A widget in the top right corner of a screen (the title screen's Edit Profile, the multiplayer
    // screen's Relays) would sit under the button row: the row then ends left of it.
    let x = guiLimit > 0 ? Math.min(right, guiLimit) : right;
    guiRow.forEach(id => {
      x -= small;
      guiRects[id] = box(x, top, small);
      x -= Math.round(gap * 0.6);
    });
    // Inventory sits right of the hotbar like Bedrock's "..." slot, or joins the top row.
    if (hotbar && hotbar.w > 0) {
      const inventory = box(hotbar.x + hotbar.w + gap * 0.5, hotbar.y + hotbar.h - small, small);
      if (inventory.x + inventory.w + gap * 0.5 <= rects.use.x && inventory.y >= top + small) {
        rects.inventory = inventory;
      }
    }
    if (!rects.inventory) {
      const last = rects[worldRow[worldRow.length - 1]];
      rects.inventory = box(last.x - Math.round(gap * 0.6) - small, top, small);
    }
    return {unit: unit, gap: gap, stick: stick, world: rects, gui: guiRects};
  }

  // Right edge (client px) the GUI button row has to keep to so that no screen widget is covered, or 0.
  // Wide widgets (tab bars, lists) span the whole row and are skipped: only the corner buttons count.
  function guiRowLimit(metrics, gui, widgets, band, gap) {
    let limit = 0;
    (widgets || []).forEach(widget => {
      if (!widget || widget.visible === false || widget.active === false) return;
      if (!(widget.width > 0) || !(widget.height > 0) || widget.width > gui.width * 0.35) return;
      const a = guiToClient(metrics, gui, widget.x, widget.y);
      const b = guiToClient(metrics, gui, widget.x + widget.width, widget.y + widget.height);
      if (b.y <= band.y || a.y >= band.y + band.h || b.x <= a.x) return;
      const edge = a.x - Math.round(gap * 0.3);
      if (!limit || edge < limit) limit = edge;
    });
    return limit;
  }

  function insideRect(rect, x, y, pad) {
    return !!rect && x >= rect.x - pad && x < rect.x + rect.w + pad && y >= rect.y - pad && y < rect.y + rect.h + pad;
  }

  // ---- pixel icons (9x9, "#" = pixel), rendered as crisp SVG paths

  const ICONS = {
    jump: ["....#....", "...###...", "..#####..", ".#######.", "...###...", "...###...", "...###...", "...###...", "........."],
    sneak: [".........", "...###...", "...###...", "...###...", "...###...", ".#######.", "..#####..", "...###...", "....#...."],
    attack: [".......##", "......###", ".....###.", "#...###..", ".#.###...", "..###....", "..##.....", ".#..#....", "#........"],
    use: [".........", ".#######.", ".#.....#.", ".#.###.#.", ".#.###.#.", ".#.###.#.", ".#.....#.", ".#######.", "........."],
    inventory: [".........", ".#######.", ".#.....#.", ".#######.", ".#..#..#.", ".#.....#.", ".#######.", ".........", "........."],
    chat: [".........", "#########", "#.......#", "#.#.#.#.#", "#.......#", "#########", "..##.....", ".##......", "........."],
    pause: [".........", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", ".##...##.", "........."],
    perspective: [".........", "...###...", ".##...##.", "#..###..#", "#..###..#", ".##...##.", "...###...", ".........", "........."],
    drop: ["....#....", "....#....", "....#....", "..#.#.#..", "...###...", "....#....", ".........", "#########", "#########"],
    settings: ["...###...", ".#.###.#.", ".#######.", "###...###", "###...###", "###...###", ".#######.", ".#.###.#.", "...###..."],
    fullscreen: ["###...###", "#.......#", "#.......#", ".........", ".........", ".........", "#.......#", "#.......#", "###...###"],
    keyboard: [".........", "#########", "#.#.#.#.#", "#########", "##.#.#.##", "#########", "#..###..#", "#########", "........."],
    back: ["##.....##", "###...###", ".###.###.", "..#####..", "...###...", "..#####..", ".###.###.", "###...###", "##.....##"]
  };

  function iconSvg(name) {
    const rows = ICONS[name] || [];
    let path = "";
    rows.forEach((row, y) => {
      let x = 0;
      while (x < row.length) {
        if (row[x] !== "#") {
          x++;
          continue;
        }
        let run = 1;
        while (row[x + run] === "#") run++;
        path += "M" + x + " " + y + "h" + run + "v1h-" + run + "z";
        x += run;
      }
    });
    return '<svg viewBox="0 0 9 9" aria-hidden="true" shape-rendering="crispEdges"><path d="' + path + '"/></svg>';
  }

  // ---- controller

  const CONTROLS = [
    {id: "jump", modes: "world", label: "Jump", hold: "key:Space"},
    {id: "sneak", modes: "world", label: "Sneak", special: "sneak"},
    {id: "attack", modes: "world", label: "Attack", hold: "button:0"},
    {id: "use", modes: "world", label: "Use", hold: "button:1"},
    {id: "inventory", modes: "world", label: "Inventory", hold: "key:KeyE"},
    {id: "pause", modes: "world", label: "Pause", hold: "key:Escape"},
    {id: "chat", modes: "world", label: "Chat", special: "chat"},
    {id: "perspective", modes: "world", label: "Perspective", hold: "key:F5"},
    {id: "drop", modes: "world", label: "Drop", special: "drop"},
    {id: "back", modes: "gui", label: "Back", hold: "key:Escape"},
    {id: "keyboard", modes: "gui", label: "Keyboard", special: "keyboard"},
    {id: "settings", modes: "world gui", label: "Touch settings", special: "settings"},
    {id: "fullscreen", modes: "world gui", label: "Fullscreen", special: "fullscreen"}
  ];
  const INPUT_KEY_CODES = {
    Enter: "Enter", Escape: "Escape", Tab: "Tab", ArrowLeft: "ArrowLeft", ArrowRight: "ArrowRight",
    ArrowUp: "ArrowUp", ArrowDown: "ArrowDown", Delete: "Delete", Home: "Home", End: "End",
    PageUp: "PageUp", PageDown: "PageDown"
  };
  const STICK_CODES = {forward: "KeyW", back: "KeyS", left: "KeyA", right: "KeyD", sprint: "ControlLeft"};

  const override = readOverride();
  const forced = override === "1";
  const touch = {
    version: 1,
    override: override,
    enabled: false,
    mode: "off",
    settings: loadSettings(),
    core: {
      stickKeys: stickKeys,
      autoGuiScale: autoGuiScale,
      guiSize: guiSize,
      toCanvas: toCanvas,
      toGui: toGui,
      hotbarRect: hotbarRect,
      hotbarSlotAt: hotbarSlotAt,
      computeLayout: computeLayout,
      guiRowLimit: guiRowLimit
    }
  };
  w.__gaiusTouch = touch;
  if (override === "0") return;

  const ui = {root: null, controls: Object.create(null), stickBase: null, stickKnob: null, panel: null, input: null, rotate: null};
  const pointers = new Map();
  const held = new Map();
  const guiMemory = {scale: 0, autoScale: 0};
  let layout = null;
  let layoutKey = "";
  let metrics = null;
  let gui = null;
  let hotbar = null;
  let stickPointer = null;
  let lastStickEnd = {at: -Infinity, forward: false};
  let sneakLatched = false;
  let syncTimer = 0;
  let keyboardOpenedAt = -Infinity;
  let keyboardSticky = false;
  let keyboardSeen = false;
  let sprintHoldOff = null;
  let lastTouchAt = -Infinity;
  let swallowMouseClick = false;
  let inputValue = SENTINEL;
  let composing = false;
  let lift = 0;
  let mouseTravel = 0;
  const inputHeldCodes = new Set();

  const api = () => w.__gaiusInput || null;
  const canvasElement = () => doc.getElementById("mc-canvas");

  // ---- input with a minimum hold: press and release in one poll would hide isDown() from a tick

  function press(id) {
    const input = api();
    if (!input) return;
    const entry = held.get(id);
    if (entry && entry.timer) {
      clearTimeout(entry.timer);
      sendRelease(id);
    }
    held.set(id, {at: now(), timer: 0});
    sendPress(id, input);
  }

  function release(id, immediate) {
    const entry = held.get(id);
    if (!entry || entry.timer) return;
    const wait = immediate ? 0 : MIN_HOLD_MS - (now() - entry.at);
    if (wait <= 0) {
      held.delete(id);
      sendRelease(id);
      return;
    }
    entry.timer = setTimeout(() => {
      if (held.get(id) !== entry) return;
      held.delete(id);
      sendRelease(id);
    }, wait);
  }

  function isHeld(id) {
    const entry = held.get(id);
    return !!entry && !entry.timer;
  }

  function sendPress(id, input) {
    const colon = id.indexOf(":");
    const kind = id.slice(0, colon);
    const value = id.slice(colon + 1);
    if (kind === "key") input.key(value, true);
    else input.button(Number(value), true);
  }

  function sendRelease(id) {
    const input = api();
    if (!input) return;
    const colon = id.indexOf(":");
    const kind = id.slice(0, colon);
    const value = id.slice(colon + 1);
    if (kind === "key") input.key(value, false);
    else input.button(Number(value), false);
  }

  function tap(id) {
    press(id);
    release(id);
  }

  function setHeld(id, on) {
    if (on && !isHeld(id)) press(id);
    else if (!on && isHeld(id)) release(id);
  }

  function releaseEverything() {
    Array.from(held.keys()).forEach(id => {
      const entry = held.get(id);
      if (entry && entry.timer) clearTimeout(entry.timer);
      held.delete(id);
      sendRelease(id);
    });
    inputHeldCodes.forEach(code => {
      const input = api();
      if (input) input.key(code, false);
    });
    inputHeldCodes.clear();
    sprintHoldOff = null;
  }

  // ---- game state

  function readMode() {
    const state = w.__gaiusMinecraftState;
    if (!state || !api()) return "off";
    const root = doc.documentElement;
    const view = root && root.dataset ? root.dataset.gaiusShellView : undefined;
    if (view) {
      if (view !== "canvas") return "off";
    } else {
      const boot = doc.getElementById("boot-screen");
      if (boot && !boot.hidden) return "off";
    }
    if (state.level && !state.screen) return "world";
    return "gui";
  }

  function readMetrics() {
    const canvas = canvasElement();
    if (!canvas || typeof canvas.getBoundingClientRect !== "function") return null;
    const box = canvas.getBoundingClientRect();
    if (!(box.width > 0) || !(box.height > 0)) return null;
    const display = w.__gaiusDisplay || {};
    const cssWidth = Number(display.cssWidth) || box.width;
    const cssHeight = Number(display.cssHeight) || box.height;
    return {
      rect: {left: box.left, top: box.top, width: box.width, height: box.height},
      cssWidth: cssWidth,
      cssHeight: cssHeight,
      framebufferWidth: Number(display.framebufferWidth) || Number(canvas.width) || cssWidth,
      framebufferHeight: Number(display.framebufferHeight) || Number(canvas.height) || cssHeight
    };
  }

  // The widget telemetry names the exact class, and vanilla subclasses EditBox anonymously (the
  // world seed field is CreateWorldScreen$WorldTab$1), so the report carries an explicit flag.
  function isEditBox(widget) {
    return !!widget && (widget.edit === true || /EditBox$/.test(String(widget.type || "")));
  }

  function textFieldWanted(state) {
    const input = api();
    const inputState = input && typeof input.state === "function" ? input.state() : null;
    if (inputState && inputState.textInput === true) return true;
    if (!state || !state.screen) return false;
    if (/ChatScreen$/.test(String(state.screen))) return true;
    const widgets = Array.isArray(state.screenWidgets) ? state.screenWidgets : [];
    return widgets.some(widget => widget && widget.focused && widget.visible !== false && isEditBox(widget));
  }

  function editBoxAt(clientX, clientY) {
    const state = w.__gaiusMinecraftState;
    if (!state || !metrics || !gui) return false;
    if (/ChatScreen$/.test(String(state.screen || ""))) return true;
    const point = toGui(metrics, gui, clientX, clientY);
    const widgets = Array.isArray(state.screenWidgets) ? state.screenWidgets : [];
    return widgets.some(widget => widget && widget.visible !== false && widget.active !== false &&
      isEditBox(widget) &&
      point.x >= widget.x && point.x < widget.x + widget.width &&
      point.y >= widget.y && point.y < widget.y + widget.height);
  }

  function safeInsets() {
    const probe = ui.safeProbe;
    if (!probe || typeof w.getComputedStyle !== "function") return {top: 0, right: 0, bottom: 0, left: 0};
    const style = w.getComputedStyle(probe);
    const read = name => parseFloat(style[name]) || 0;
    return {top: read("paddingTop"), right: read("paddingRight"), bottom: read("paddingBottom"), left: read("paddingLeft")};
  }

  function viewportSize() {
    return {width: w.innerWidth || 0, height: w.innerHeight || 0};
  }

  // ---- DOM

  function element(tag, className, parent) {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (parent) parent.appendChild(node);
    return node;
  }

  function stopMouse(node) {
    ["mousedown", "mouseup", "mousemove", "click", "dblclick", "wheel", "contextmenu"].forEach(type => {
      node.addEventListener(type, event => event.stopPropagation());
    });
  }

  function build() {
    if (ui.root) return;
    const root = element("div", "gt-root", null);
    root.id = "gaius-touch";
    root.setAttribute("data-mode", "off");
    root.setAttribute("aria-hidden", "true");
    ui.root = root;
    ui.safeProbe = element("div", "gt-safe", root);
    ui.stickBase = element("div", "gt-stick", root);
    ui.stickBase.setAttribute("data-modes", "world");
    ui.stickKnob = element("div", "gt-knob", ui.stickBase);
    CONTROLS.forEach(control => {
      const node = element("div", "gt-ctl gt-" + control.id, root);
      node.setAttribute("data-modes", control.modes);
      node.setAttribute("data-control", control.id);
      node.setAttribute("title", control.label);
      node.innerHTML = iconSvg(control.id);
      ui.controls[control.id] = node;
    });
    if (!fullscreenSupported()) ui.controls.fullscreen.setAttribute("data-unsupported", "1");
    ui.rotate = element("div", "gt-rotate", root);
    ui.rotate.textContent = "Rotate your device to landscape";
    buildPanel(root);
    buildTextInput();
    root.addEventListener("touchstart", onTouchStart, {passive: false});
    root.addEventListener("touchmove", onTouchMove, {passive: false});
    root.addEventListener("touchend", onTouchEnd, {passive: false});
    root.addEventListener("touchcancel", onTouchCancel, {passive: false});
    root.addEventListener("contextmenu", event => event.preventDefault());
    (doc.body || doc.documentElement).appendChild(root);
    applySettings();
  }

  function buildPanel(root) {
    const panel = element("div", "gt-panel", root);
    panel.hidden = true;
    stopMouse(panel);
    const title = element("div", "gt-panel-title", panel);
    title.textContent = "TOUCH CONTROLS";
    const rows = [
      ["opacity", "Opacity", 0.05, value => Math.round(value * 100) + "%"],
      ["size", "Size", 0.05, value => Math.round(value * 100) + "%"],
      ["sensitivity", "Look speed", 0.1, value => value.toFixed(1) + "x"]
    ];
    ui.sliders = {};
    rows.forEach(row => {
      const name = row[0];
      const label = element("label", "gt-panel-row", panel);
      const text = element("span", "gt-panel-label", label);
      text.textContent = row[1];
      const input = element("input", "gt-panel-range", label);
      input.type = "range";
      input.min = String(LIMITS[name][0]);
      input.max = String(LIMITS[name][1]);
      input.step = String(row[2]);
      const value = element("span", "gt-panel-value", label);
      input.addEventListener("input", () => {
        const number = Number(input.value);
        if (!Number.isFinite(number)) return;
        touch.settings[name] = clamp(number, LIMITS[name][0], LIMITS[name][1]);
        saveSettings();
        applySettings();
      });
      ui.sliders[name] = {input: input, value: value, format: row[3]};
    });
    const actions = element("div", "gt-panel-actions", panel);
    const reset = element("button", "gt-panel-button", actions);
    reset.type = "button";
    reset.textContent = "Reset";
    reset.addEventListener("click", () => {
      touch.settings = {opacity: DEFAULTS.opacity, size: DEFAULTS.size, sensitivity: DEFAULTS.sensitivity};
      saveSettings();
      applySettings();
    });
    const done = element("button", "gt-panel-button", actions);
    done.type = "button";
    done.textContent = "Done";
    done.addEventListener("click", () => togglePanel(false));
    ui.panel = panel;
  }

  function buildTextInput() {
    const input = element("input", "gt-text", null);
    input.type = "text";
    input.id = "gaius-touch-text";
    input.setAttribute("autocomplete", "off");
    input.setAttribute("autocorrect", "off");
    input.setAttribute("autocapitalize", "off");
    input.setAttribute("spellcheck", "false");
    input.setAttribute("enterkeyhint", "send");
    input.setAttribute("aria-label", "Game text input");
    input.value = SENTINEL;
    input.addEventListener("input", () => {
      diffInput();
      if (!composing) resetInputIfNeeded();
    });
    input.addEventListener("compositionstart", () => { composing = true; });
    input.addEventListener("compositionend", () => {
      composing = false;
      diffInput();
      resetInputIfNeeded();
    });
    input.addEventListener("keydown", onInputKeyDown);
    input.addEventListener("keyup", onInputKeyUp);
    input.addEventListener("blur", () => {
      const game = api();
      inputHeldCodes.forEach(code => { if (game) game.key(code, false); });
      inputHeldCodes.clear();
      updateLift();
    });
    stopMouse(input);
    (doc.body || doc.documentElement).appendChild(input);
    ui.input = input;
  }

  function saveSettings() {
    storage.set(SETTINGS_KEY, JSON.stringify(touch.settings));
  }

  function applySettings() {
    if (!ui.root) return;
    if (ui.root.style && typeof ui.root.style.setProperty === "function") {
      ui.root.style.setProperty("--gt-opacity", String(touch.settings.opacity));
    }
    Object.keys(ui.sliders || {}).forEach(name => {
      const slider = ui.sliders[name];
      slider.input.value = String(touch.settings[name]);
      slider.value.textContent = slider.format(touch.settings[name]);
    });
    layoutKey = "";
    relayout();
  }

  function place(node, rect) {
    if (!node || !rect) return;
    node.style.width = rect.w + "px";
    node.style.height = rect.h + "px";
    node.style.transform = "translate3d(" + rect.x + "px," + rect.y + "px,0)";
  }

  function relayout() {
    if (!ui.root) return;
    const view = viewportSize();
    const safe = safeInsets();
    let guiLimit = 0;
    if (touch.mode === "gui" && metrics && gui) {
      const state = w.__gaiusMinecraftState;
      const probe = computeLayout(view.width, view.height, touch.settings.size, safe, hotbar, fullscreenSupported(), 0);
      const band = probe.gui.back;
      const widgets = state && Array.isArray(state.screenWidgets) ? state.screenWidgets : null;
      if (band && widgets) {
        guiLimit = Math.round(guiRowLimit(metrics, gui, widgets, band, probe.gap));
        // Never squeeze the row into a sliver: a limit that leaves under a third of the width is ignored.
        if (guiLimit < view.width * 0.3) guiLimit = 0;
      }
    }
    const key = [view.width, view.height, safe.top, safe.right, safe.bottom, safe.left, touch.settings.size, guiLimit,
      hotbar ? [Math.round(hotbar.x), Math.round(hotbar.y), Math.round(hotbar.w), Math.round(hotbar.h)].join(",") : "-"].join("|");
    if (key === layoutKey && layout) return;
    layoutKey = key;
    layout = computeLayout(view.width, view.height, touch.settings.size, safe, hotbar, fullscreenSupported(), guiLimit);
    const rects = touch.mode === "gui" ? Object.assign({}, layout.world, layout.gui) : layout.world;
    CONTROLS.forEach(control => {
      if (rects[control.id]) place(ui.controls[control.id], rects[control.id]);
    });
    const stick = layout.stick;
    place(ui.stickBase, {x: stick.cx - stick.r, y: stick.cy - stick.r, w: stick.r * 2, h: stick.r * 2});
    const knob = Math.round(stick.r * 0.9);
    ui.stickKnob.style.width = knob + "px";
    ui.stickKnob.style.height = knob + "px";
    ui.stickKnob.style.left = Math.round(stick.r - knob / 2) + "px";
    ui.stickKnob.style.top = Math.round(stick.r - knob / 2) + "px";
    ui.rotate.hidden = !(view.height > view.width && Math.min(view.width, view.height) < 700);
    // Below the top button row: at the top it would sit on the pause and chat buttons.
    const pause = layout.world.pause;
    ui.rotate.style.top = pause ? (pause.y + pause.h + Math.round(layout.gap * 0.5)) + "px" : "";
  }

  function setPressed(id, on) {
    const node = ui.controls[id];
    if (node) node.setAttribute("data-on", on ? "1" : "0");
  }

  // ---- mode synchronisation (polled; the game state report itself runs every 50-100 ms)

  function sync() {
    if (!touch.enabled) return;
    const mode = readMode();
    if (mode !== touch.mode) {
      cancelAllPointers();
      releaseEverything();
      if (sneakLatched) {
        sneakLatched = false;
        setPressed("sneak", false);
      }
      if (mode === "off") togglePanel(false);
      keyboardSticky = false;
      touch.mode = mode;
      if (ui.root) ui.root.setAttribute("data-mode", mode);
      layoutKey = "";
    }
    if (mode === "off") {
      if (keyboardOpen()) closeKeyboard();
      return;
    }
    metrics = readMetrics();
    const state = w.__gaiusMinecraftState;
    gui = metrics ? guiSize(metrics, state, guiMemory) : null;
    hotbar = mode === "world" && metrics && gui ? hotbarRect(metrics, gui) : null;
    fitCanvasToViewport();
    relayout();
    if (keyboardOpen() && !keyboardSticky && now() - keyboardOpenedAt > KEYBOARD_GRACE_MS &&
        !(mode === "gui" && textFieldWanted(state))) {
      closeKeyboard();
    }
    updateLift();
  }

  // The launcher opens the game window at 854x480 or larger; a phone needs its own viewport
  // size or part of the GUI stays off-screen until the first resize event.
  function fitCanvasToViewport() {
    const display = w.__gaiusDisplay;
    if (!display || typeof w.__gaiusApplyCanvasResolution !== "function" || keyboardOpen()) return;
    const view = viewportSize();
    if (!(view.width > 0) || !(view.height > 0)) return;
    if (Math.abs(display.cssWidth - view.width) > 1 || Math.abs(display.cssHeight - view.height) > 1) {
      w.__gaiusApplyCanvasResolution(view.width, view.height, true);
      metrics = readMetrics();
    }
  }

  // ---- touch dispatch

  function onTouchStart(event) {
    if (ui.panel && !ui.panel.hidden && contains(ui.panel, event.target)) return;
    event.preventDefault();
    sync();
    const time = now();
    const touches = event.changedTouches || [];
    for (let i = 0; i < touches.length; i++) {
      startPointer(touches[i].identifier, touches[i].clientX, touches[i].clientY, time);
    }
  }

  function onTouchMove(event) {
    const touches = event.changedTouches || [];
    let tracked = false;
    for (let i = 0; i < touches.length; i++) {
      if (pointers.has(touches[i].identifier)) {
        tracked = true;
        movePointer(touches[i].identifier, touches[i].clientX, touches[i].clientY);
      }
    }
    if (tracked) event.preventDefault();
  }

  function onTouchEnd(event) {
    finishTouches(event, false);
  }

  function onTouchCancel(event) {
    finishTouches(event, true);
  }

  function finishTouches(event, cancelled) {
    const touches = event.changedTouches || [];
    let tracked = false;
    const time = now();
    for (let i = 0; i < touches.length; i++) {
      if (pointers.has(touches[i].identifier)) {
        tracked = true;
        endPointer(touches[i].identifier, touches[i].clientX, touches[i].clientY, time, cancelled);
      }
    }
    if (tracked && event.cancelable !== false) event.preventDefault();
  }

  function contains(parent, node) {
    if (!parent || !node) return false;
    if (typeof parent.contains === "function") return parent.contains(node);
    for (let current = node; current; current = current.parentNode) {
      if (current === parent) return true;
    }
    return false;
  }

  function hitControl(x, y) {
    if (!layout) return null;
    const rects = touch.mode === "gui" ? layout.gui : layout.world;
    const pad = Math.round(layout.gap * 0.3);
    for (let i = 0; i < CONTROLS.length; i++) {
      const control = CONTROLS[i];
      if ((" " + control.modes + " ").indexOf(" " + touch.mode + " ") < 0) continue;
      if (insideRect(rects[control.id], x, y, pad)) return control;
    }
    return null;
  }

  function startPointer(id, x, y, time) {
    if (touch.mode === "off") return;
    const control = hitControl(x, y);
    if (control) {
      startControl(id, control, x, y, time);
      return;
    }
    if (touch.mode === "world") {
      const slot = metrics && gui ? hotbarSlotAt(metrics, gui, x, y) : -1;
      if (slot >= 0) {
        tap("key:Digit" + (slot + 1));
        pointers.set(id, {kind: "hotbar", slot: slot});
        return;
      }
      if (!stickPointer && inStickZone(x, y)) {
        startStick(id, x, y, time);
        return;
      }
      startLook(id, x, y, time);
      return;
    }
    startGui(id, x, y, time);
  }

  function movePointer(id, x, y) {
    const pointer = pointers.get(id);
    if (!pointer) return;
    if (pointer.kind === "stick") updateStick(pointer, x, y);
    else if (pointer.kind === "look") moveLook(pointer, x, y);
    else if (pointer.kind === "gui") moveGui(pointer, x, y);
    else if (pointer.kind === "hotbar" && metrics && gui) {
      const slot = hotbarSlotAt(metrics, gui, x, y);
      if (slot >= 0 && slot !== pointer.slot) {
        pointer.slot = slot;
        tap("key:Digit" + (slot + 1));
      }
    } else if (pointer.kind === "control") {
      pointer.inside = insideRect(controlRect(pointer.control.id), x, y, layout ? layout.gap : 0);
    }
  }

  function endPointer(id, x, y, time, cancelled) {
    const pointer = pointers.get(id);
    pointers.delete(id);
    if (!pointer) return;
    if (pointer.kind === "stick") endStick(pointer, time);
    else if (pointer.kind === "look") endLook(pointer, time, cancelled);
    else if (pointer.kind === "gui") endGui(pointer, x, y, cancelled);
    else if (pointer.kind === "control") endControl(pointer, time, cancelled);
  }

  // Mode changes (a screen opened or closed) end every gesture: held keys are released and the
  // touches are ignored until they lift.
  function cancelAllPointers() {
    pointers.forEach((pointer, id) => {
      if (pointer.kind === "stick") endStick(pointer, now());
      else if (pointer.kind === "look") endLook(pointer, now(), true);
      else if (pointer.kind === "gui") endGui(pointer, pointer.x, pointer.y, true);
      else if (pointer.kind === "control") endControl(pointer, now(), true);
      pointers.set(id, {kind: "none"});
    });
    stickPointer = null;
  }

  // ---- on-screen buttons

  function controlRect(id) {
    if (!layout) return null;
    return touch.mode === "gui" && layout.gui[id] ? layout.gui[id] : layout.world[id];
  }

  function startControl(id, control, x, y, time) {
    const pointer = {kind: "control", control: control, start: time, inside: true, timer: 0, done: false};
    pointers.set(id, pointer);
    setPressed(control.id, true);
    if (control.hold) {
      press(control.hold);
      return;
    }
    if (control.special === "sneak") {
      pointer.wasLatched = sneakLatched;
      if (!sneakLatched) press("key:ShiftLeft");
    } else if (control.special === "drop") {
      pointer.timer = setTimeout(() => {
        if (pointers.get(id) !== pointer) return;
        pointer.done = true;
        const sprinting = isHeld("key:ControlLeft");
        if (!sprinting) press("key:ControlLeft");
        tap("key:KeyQ");
        if (!sprinting) release("key:ControlLeft");
      }, DROP_STACK_MS);
    }
  }

  function endControl(pointer, time, cancelled) {
    const control = pointer.control;
    if (pointer.timer) clearTimeout(pointer.timer);
    if (control.hold) {
      release(control.hold);
      setPressed(control.id, false);
      return;
    }
    if (control.special === "sneak") {
      const momentary = time - pointer.start >= SNEAK_HOLD_MS;
      if (cancelled || momentary || pointer.wasLatched) {
        sneakLatched = false;
        release("key:ShiftLeft");
      } else {
        sneakLatched = true;
      }
      setPressed("sneak", sneakLatched);
      return;
    }
    setPressed(control.id, false);
    if (cancelled || !pointer.inside) return;
    // Activation-gated actions (keyboard focus, fullscreen) run from touchend.
    if (control.special === "drop") {
      if (!pointer.done) dropOne();
    } else if (control.special === "chat") {
      tap("key:KeyT");
      openKeyboard();
    } else if (control.special === "keyboard") {
      if (keyboardShown()) {
        closeKeyboard();
      } else {
        openKeyboard();
        keyboardSticky = true;
      }
    } else if (control.special === "settings") {
      togglePanel(ui.panel.hidden);
    } else if (control.special === "fullscreen") {
      toggleFullscreen();
    }
  }

  // Ctrl+Q drops the stack; a sprinting joystick holds Ctrl, so let go of it for a single drop.
  // The game reads Ctrl live in the tick that consumes the Q click, so the joystick must not press
  // it again until Q is up, the minimum hold plus a gap has passed and a few frames (each one at
  // least a tick's worth of time) have run, however often the stick finger moves meanwhile.
  function dropOne() {
    const sprinting = isHeld("key:" + STICK_CODES.sprint);
    if (sprinting) {
      release("key:" + STICK_CODES.sprint, true);
      holdOffSprint();
    }
    tap("key:KeyQ");
  }

  function holdOffSprint() {
    const token = {until: now() + MIN_HOLD_MS + DROP_SPRINT_GAP_MS, frames: 0};
    sprintHoldOff = token;
    const step = () => {
      if (sprintHoldOff !== token) return;
      token.frames++;
      if (token.frames < 3 || now() < token.until || held.has("key:KeyQ")) {
        nextFrame(step);
        return;
      }
      sprintHoldOff = null;
      if (stickPointer) refreshStick(stickPointer);
    };
    nextFrame(step);
  }

  function nextFrame(fn) {
    if (typeof w.requestAnimationFrame === "function") w.requestAnimationFrame(fn);
    else setTimeout(fn, 16);
  }

  // ---- joystick

  function inStickZone(x, y) {
    if (!layout) return false;
    const view = viewportSize();
    const stick = layout.stick;
    if (Math.hypot(x - stick.cx, y - stick.cy) <= stick.r * 1.3) return true;
    return x < view.width * 0.4 && y > view.height * 0.35;
  }

  function startStick(id, x, y, time) {
    const stick = layout.stick;
    let cx = stick.cx;
    let cy = stick.cy;
    // Touches away from the resting base move the base under the thumb (floating stick).
    if (Math.hypot(x - cx, y - cy) > stick.r * 1.3) {
      const view = viewportSize();
      cx = clamp(x, stick.r, Math.max(stick.r, view.width - stick.r));
      cy = clamp(y, stick.r, Math.max(stick.r, view.height - stick.r));
    }
    const latched = time - lastStickEnd.at <= DOUBLE_TAP_MS && lastStickEnd.forward;
    const pointer = {kind: "stick", cx: cx, cy: cy, x: x, y: y, start: time, latched: latched, forward: false};
    pointers.set(id, pointer);
    stickPointer = pointer;
    ui.stickBase.setAttribute("data-on", "1");
    if (cx !== stick.cx || cy !== stick.cy) {
      place(ui.stickBase, {x: Math.round(cx - stick.r), y: Math.round(cy - stick.r), w: stick.r * 2, h: stick.r * 2});
    }
    updateStick(pointer, x, y);
  }

  function updateStick(pointer, x, y) {
    pointer.x = x;
    pointer.y = y;
    refreshStick(pointer);
  }

  function refreshStick(pointer) {
    const radius = layout ? layout.stick.r : 1;
    const dx = pointer.x - pointer.cx;
    const dy = pointer.y - pointer.cy;
    const keys = stickKeys(dx, dy, radius, pointer.latched);
    if (keys.forward) pointer.forward = true;
    setHeld("key:" + STICK_CODES.forward, keys.forward);
    setHeld("key:" + STICK_CODES.back, keys.back);
    setHeld("key:" + STICK_CODES.left, keys.left);
    setHeld("key:" + STICK_CODES.right, keys.right);
    setHeld("key:" + STICK_CODES.sprint, keys.sprint && !sprintHoldOff);
    const distance = Math.hypot(dx, dy);
    const scale = distance > radius ? radius / distance : 1;
    ui.stickKnob.style.transform = "translate3d(" + Math.round(dx * scale) + "px," + Math.round(dy * scale) + "px,0)";
    ui.stickKnob.setAttribute("data-sprint", keys.sprint ? "1" : "0");
  }

  function endStick(pointer, time) {
    if (stickPointer === pointer) stickPointer = null;
    lastStickEnd = {at: time, forward: pointer.forward && time - pointer.start <= DOUBLE_TAP_MS * 2};
    ["forward", "back", "left", "right", "sprint"].forEach(name => setHeld("key:" + STICK_CODES[name], false));
    ui.stickKnob.style.transform = "translate3d(0,0,0)";
    ui.stickKnob.setAttribute("data-sprint", "0");
    ui.stickBase.setAttribute("data-on", "0");
    layoutKey = "";
    relayout();
  }

  // ---- camera: drag to look, tap to use, hold still to attack

  function lookScale() {
    const scale = metrics ? metrics.cssWidth / metrics.rect.width : 1;
    return scale * touch.settings.sensitivity;
  }

  function startLook(id, x, y, time) {
    const pointer = {kind: "look", sx: x, sy: y, x: x, y: y, start: time, moved: false, attacking: false, timer: 0};
    pointer.timer = setTimeout(() => {
      if (pointers.get(id) !== pointer || pointer.moved) return;
      pointer.attacking = true;
      press("button:0");
    }, BREAK_HOLD_MS);
    pointers.set(id, pointer);
  }

  function moveLook(pointer, x, y) {
    const dx = x - pointer.x;
    const dy = y - pointer.y;
    pointer.x = x;
    pointer.y = y;
    if (!pointer.moved && Math.hypot(x - pointer.sx, y - pointer.sy) > SLOP_PX) {
      pointer.moved = true;
      if (!pointer.attacking) clearTimeout(pointer.timer);
    }
    const input = api();
    if (input && (dx || dy)) {
      const scale = lookScale();
      input.look(dx * scale, dy * scale);
    }
  }

  function endLook(pointer, time, cancelled) {
    clearTimeout(pointer.timer);
    if (pointer.attacking) {
      release("button:0");
    } else if (!cancelled && !pointer.moved && time - pointer.start < TAP_MS) {
      tap("button:1");
    }
  }

  // ---- GUI screens: tap = left click, long press = right click, drag = drag, two fingers = wheel

  // Right clicks only do something on container screens (split a stack, place one item). Vanilla
  // buttons and text fields react to the left button alone, so elsewhere a slow tap stays a tap.
  function rightClickScreen() {
    const state = w.__gaiusMinecraftState;
    const name = String(state && state.screen || "");
    return /\.screens\.inventory\.\w+Screen$/.test(name) && !/(?:Edit|Lectern|Book\w*)Screen$/.test(name);
  }

  function guiPointerPhase(phase) {
    let found = null;
    pointers.forEach(pointer => {
      if (!found && pointer.kind === "gui" && pointer.phase === phase) found = pointer;
    });
    return found;
  }

  function movePointerTo(x, y) {
    const input = api();
    if (!input || !metrics) return;
    const point = toCanvas(metrics, x, y);
    input.pointer(point.x, point.y);
  }

  function startGui(id, x, y, time) {
    const pending = guiPointerPhase("pending");
    if (pending) {
      clearTimeout(pending.timer);
      pending.phase = "scroll";
      const partner = {kind: "gui", phase: "scroll", x: x, y: y, partner: pending};
      pending.partner = partner;
      partner.wheel = pending.wheel = {lastY: (pending.y + y) / 2, carry: 0};
      pointers.set(id, partner);
      return;
    }
    if (guiPointerPhase("drag") || guiPointerPhase("right") || guiPointerPhase("scroll")) {
      pointers.set(id, {kind: "none"});
      return;
    }
    const pointer = {kind: "gui", phase: "pending", sx: x, sy: y, x: x, y: y, start: time, timer: 0};
    pointer.timer = setTimeout(() => {
      if (pointers.get(id) !== pointer || pointer.phase !== "pending" || !rightClickScreen()) return;
      pointer.phase = "right";
      movePointerTo(pointer.x, pointer.y);
      const input = api();
      if (input) input.button(1, true);
    }, LONG_PRESS_MS);
    pointers.set(id, pointer);
    movePointerTo(x, y);
  }

  function moveGui(pointer, x, y) {
    pointer.x = x;
    pointer.y = y;
    const input = api();
    if (!input) return;
    if (pointer.phase === "pending" && Math.hypot(x - pointer.sx, y - pointer.sy) > SLOP_PX) {
      clearTimeout(pointer.timer);
      pointer.phase = "drag";
      movePointerTo(pointer.sx, pointer.sy);
      input.button(0, true);
      movePointerTo(x, y);
    } else if (pointer.phase === "drag" || pointer.phase === "right") {
      movePointerTo(x, y);
    } else if (pointer.phase === "scroll" && pointer.partner) {
      const wheel = pointer.wheel;
      const averageY = (pointer.y + pointer.partner.y) / 2;
      wheel.carry += averageY - wheel.lastY;
      wheel.lastY = averageY;
      const steps = wheel.carry > 0 ? Math.floor(wheel.carry / WHEEL_STEP_PX) : Math.ceil(wheel.carry / WHEEL_STEP_PX);
      if (steps) {
        wheel.carry -= steps * WHEEL_STEP_PX;
        movePointerTo((pointer.x + pointer.partner.x) / 2, averageY);
        // Fingers moving down pull the content down, like a wheel turned away from the user.
        input.wheel(0, steps);
      }
    }
  }

  function endGui(pointer, x, y, cancelled) {
    clearTimeout(pointer.timer);
    const input = api();
    const phase = pointer.phase;
    pointer.phase = "done";
    if (!input) return;
    if (phase === "pending") {
      if (cancelled) return;
      movePointerTo(x, y);
      input.button(0, true);
      input.button(0, false);
      // Keyboard focus needs the user activation of this touchend.
      if (editBoxAt(x, y)) openKeyboard();
    } else if (phase === "drag") {
      movePointerTo(x, y);
      input.button(0, false);
    } else if (phase === "right") {
      movePointerTo(x, y);
      input.button(1, false);
    } else if (phase === "scroll" && pointer.partner) {
      pointer.partner.phase = "done";
      pointer.partner.partner = null;
    }
  }

  // ---- virtual keyboard: a hidden input forwards text and editing keys to the game

  // The hidden input has focus. Android hides the keyboard (back button, the keyboard's own hide
  // key) without blurring it, so this alone does not mean the keyboard is on screen.
  function keyboardOpen() {
    return !!ui.input && doc.activeElement === ui.input;
  }

  // Height of the layout viewport hidden below the visual viewport (the on-screen keyboard).
  function keyboardCover() {
    const viewport = w.visualViewport;
    if (!viewport) return 0;
    return (w.innerHeight || 0) - (viewport.height + viewport.offsetTop);
  }

  // Focused and not known to be hidden: a keyboard that was seen and went away without a blur
  // counts as closed. A hardware keyboard never covers the viewport and counts as open.
  function keyboardShown() {
    return keyboardOpen() && !(keyboardSeen && keyboardCover() <= KEYBOARD_MIN_PX);
  }

  // SDL only queues TEXT_INPUT while SDL_StartTextInput is active. Vanilla starts it for
  // ChatScreen, but ordinary EditBox fields (notably Create World's anonymous seed field) do not
  // start it. The virtual DOM input is already focused for those fields, so keep SDL text input
  // aligned with the touch keyboard lifetime. GLFW has no SDL object and remains unchanged.
  function setTouchTextInput(on) {
    const sdl = w.__gaiusSdl;
    if (sdl && typeof sdl.setTextInput === "function") sdl.setTextInput(!!on);
  }

  function openKeyboard() {
    const input = ui.input;
    if (!input) return;
    setTouchTextInput(true);
    keyboardOpenedAt = now();
    if (keyboardOpen()) {
      if (keyboardCover() > KEYBOARD_MIN_PX) return;
      // Focusing the focused input does not bring a hidden keyboard back; a fresh focus inside
      // this user gesture does.
      if (typeof input.blur === "function") input.blur();
    }
    keyboardSeen = false;
    input.value = SENTINEL;
    inputValue = SENTINEL;
    composing = false;
    try {
      input.focus({preventScroll: true});
    } catch (error) {
      input.focus();
    }
    try {
      input.setSelectionRange(SENTINEL.length, SENTINEL.length);
    } catch (error) {
      // Selection is cosmetic.
    }
    updateLift();
  }

  function closeKeyboard() {
    keyboardSticky = false;
    if (ui.input && doc.activeElement === ui.input && typeof ui.input.blur === "function") ui.input.blur();
    setTouchTextInput(false);
    updateLift();
  }

  function codePoints(text) {
    return Array.from(text).length;
  }

  // Text arrives as a value change: whatever was removed after the common prefix becomes
  // Backspace presses and whatever was added becomes text. This covers typing, autocorrect,
  // predictive and IME composition (updated live) and paste with one path.
  function diffInput() {
    const input = ui.input;
    const game = api();
    setTouchTextInput(true);
    const value = String(input.value);
    const previous = inputValue;
    inputValue = value;
    if (!game) return;
    let prefix = 0;
    const limit = Math.min(previous.length, value.length);
    while (prefix < limit && previous.charCodeAt(prefix) === value.charCodeAt(prefix)) prefix++;
    if (prefix > 0 && (previous.charCodeAt(prefix - 1) & 0xFC00) === 0xD800) prefix--;
    const removed = codePoints(previous.slice(prefix));
    const added = value.slice(prefix).replace(/\u200b/g, "");
    for (let i = 0; i < removed; i++) {
      game.key("Backspace", true);
      game.key("Backspace", false);
    }
    if (added) game.text(added);
  }

  function resetInputIfNeeded() {
    const input = ui.input;
    const value = String(input.value);
    if (value.length < SENTINEL.length || value.slice(0, SENTINEL.length) !== SENTINEL || value.length > 64) {
      input.value = SENTINEL;
      inputValue = SENTINEL;
    }
  }

  function onInputKeyDown(event) {
    // The bridges listen on window; keys typed here are forwarded below or arrive as text.
    event.stopPropagation();
    if (event.isComposing || event.keyCode === 229) return;
    const game = api();
    if (!game) return;
    const named = INPUT_KEY_CODES[event.key];
    if (named) {
      event.preventDefault();
      const code = named;
      if (code === "Enter" || code === "Tab") {
        game.key(code, true);
        game.key(code, false);
        if (code === "Enter") {
          ui.input.value = SENTINEL;
          inputValue = SENTINEL;
        }
        return;
      }
      if (!inputHeldCodes.has(code)) {
        inputHeldCodes.add(code);
        game.key(code, true);
      }
      return;
    }
    // Shortcuts such as Ctrl+A / Ctrl+C on a hardware keyboard. Paste stays native and arrives
    // as text through the input event.
    const shortcut = (event.ctrlKey || event.metaKey) && /^Key[ACXZY]$/.test(String(event.code || ""));
    if (shortcut) {
      event.preventDefault();
      game.key("ControlLeft", true);
      game.key(event.code, true);
      game.key(event.code, false);
      game.key("ControlLeft", false);
    }
  }

  function onInputKeyUp(event) {
    event.stopPropagation();
    const named = INPUT_KEY_CODES[event.key];
    const game = api();
    if (named && inputHeldCodes.has(named)) {
      inputHeldCodes.delete(named);
      if (game) game.key(named, false);
    }
  }

  // Mobile keyboards overlay the page (interactive-widget=resizes-visual); lift the canvas so the
  // bottom of the game (chat, sign and book fields) stays visible above the keyboard.
  function updateLift() {
    let next = 0;
    if (keyboardOpen()) {
      const covered = keyboardCover();
      if (covered > KEYBOARD_MIN_PX) {
        keyboardSeen = true;
        next = Math.round(covered);
      }
    }
    if (next === lift) return;
    lift = next;
    const canvas = canvasElement();
    if (canvas && canvas.style) canvas.style.transform = lift ? "translate3d(0," + (-lift) + "px,0)" : "";
    metrics = readMetrics();
  }

  // ---- settings, fullscreen

  function togglePanel(open) {
    if (!ui.panel) return;
    ui.panel.hidden = !open;
    setPressed("settings", !!open);
  }

  function fullscreenSupported() {
    const root = doc.documentElement;
    return !!(root && (root.requestFullscreen || root.webkitRequestFullscreen));
  }

  function toggleFullscreen() {
    const current = doc.fullscreenElement || doc.webkitFullscreenElement;
    try {
      if (current) {
        const exit = doc.exitFullscreen || doc.webkitExitFullscreen;
        const result = exit && exit.call(doc);
        if (result && result.catch) result.catch(() => {});
        return;
      }
      const root = doc.documentElement;
      const request = root.requestFullscreen || root.webkitRequestFullscreen;
      if (!request) return;
      const result = request.call(root, {navigationUI: "hide"});
      if (result && result.then) result.then(lockLandscape, () => {});
      else lockLandscape();
    } catch (error) {
      // Fullscreen is best effort.
    }
  }

  function lockLandscape() {
    try {
      const orientation = w.screen && w.screen.orientation;
      if (orientation && orientation.lock) {
        const result = orientation.lock("landscape");
        if (result && result.catch) result.catch(() => {});
      }
    } catch (error) {
      // Orientation lock is only available in fullscreen on some browsers.
    }
  }

  // ---- activation lifecycle

  function activate(reason) {
    if (touch.enabled) return;
    touch.enabled = true;
    touch.reason = reason;
    w.__gaiusTouchActive = true;
    if (doc.documentElement && doc.documentElement.classList) doc.documentElement.classList.add("gaius-touch");
    build();
    ui.root.hidden = false;
    // A pointer lock taken before the controls appeared would swallow nothing on touch, but the
    // virtual lock replaces it, so release it.
    if (doc.pointerLockElement && doc.exitPointerLock) {
      try {
        doc.exitPointerLock();
      } catch (error) {
        // Best effort.
      }
    }
    sync();
    if (!syncTimer) syncTimer = setInterval(sync, 100);
  }

  function deactivate(reason) {
    if (!touch.enabled || forced) return;
    cancelAllPointers();
    pointers.clear();
    releaseEverything();
    closeKeyboard();
    togglePanel(false);
    sneakLatched = false;
    setPressed("sneak", false);
    touch.enabled = false;
    touch.reason = reason;
    touch.mode = "off";
    w.__gaiusTouchActive = false;
    if (ui.root) {
      ui.root.setAttribute("data-mode", "off");
      ui.root.hidden = true;
    }
    if (doc.documentElement && doc.documentElement.classList) doc.documentElement.classList.remove("gaius-touch");
    if (syncTimer) {
      clearInterval(syncTimer);
      syncTimer = 0;
    }
  }

  function releaseForBackground() {
    if (!touch.enabled) return;
    cancelAllPointers();
    releaseEverything();
    // Shift is up now, so the sneak latch must not stay lit (the next tap would only unlatch).
    if (sneakLatched) {
      sneakLatched = false;
      setPressed("sneak", false);
    }
    const game = api();
    if (game && typeof game.releaseAll === "function") game.releaseAll();
  }

  w.addEventListener("touchstart", () => {
    lastTouchAt = now();
    if (!touch.enabled) activate("touch");
  }, {capture: true, passive: true});
  ["touchend", "touchcancel"].forEach(type => {
    w.addEventListener(type, () => { lastTouchAt = now(); }, {capture: true, passive: true});
  });
  // A real mouse on a hybrid device hands control back to the desktop path (auto mode only).
  w.addEventListener("pointerdown", event => {
    swallowMouseClick = false;
    if (!touch.enabled || event.pointerType !== "mouse") return;
    const overOverlay = touch.mode !== "off" && contains(ui.root, event.target);
    deactivate("mouse");
    if (touch.enabled || !overOverlay || !w.__gaiusWantPointerLock) return;
    // The click landed on the overlay, so the bridges would see a click without pointer lock (an
    // attack in the world) and would not take the lock. Take it here, within this gesture, and
    // keep the click itself from the game, like the first click that grabs the mouse on desktop.
    swallowMouseClick = true;
    const canvas = canvasElement();
    if (canvas && typeof canvas.requestPointerLock === "function" && doc.pointerLockElement !== canvas) {
      try {
        const result = canvas.requestPointerLock();
        if (result && result.catch) result.catch(() => {});
      } catch (error) {
        // The bridges request the lock again on the next canvas click.
      }
    }
  }, true);
  // Mouse events the bridges' window listeners must not see: those of the hand-back click above,
  // and the compatibility events a browser synthesises for a tap that started outside the overlay
  // (the touch that activated the controls) and then lands on the canvas or the overlay.
  function compatibilityMouse(event) {
    const caps = event.sourceCapabilities;
    if (caps && typeof caps.firesTouchEvents === "boolean") return caps.firesTouchEvents;
    return now() - lastTouchAt < COMPAT_MOUSE_MS;
  }
  ["mousedown", "mouseup", "mousemove"].forEach(type => {
    w.addEventListener(type, event => {
      let swallow = false;
      if (swallowMouseClick && type !== "mousemove") {
        swallow = true;
        if (type === "mouseup") swallowMouseClick = false;
      } else if (touch.enabled && compatibilityMouse(event)) {
        const target = event.target;
        swallow = target === canvasElement() || (contains(ui.root, target) && !contains(ui.panel, target));
      }
      if (!swallow) return;
      if (typeof event.stopImmediatePropagation === "function") event.stopImmediatePropagation();
      if (event.cancelable !== false && typeof event.preventDefault === "function") event.preventDefault();
    }, true);
  });
  w.addEventListener("pointermove", event => {
    if (!touch.enabled || event.pointerType !== "mouse") return;
    mouseTravel += Math.abs(Number(event.movementX) || 0) + Math.abs(Number(event.movementY) || 0);
    if (mouseTravel > 48) {
      mouseTravel = 0;
      deactivate("mouse");
    }
  }, true);
  w.addEventListener("blur", releaseForBackground);
  w.addEventListener("pagehide", releaseForBackground);
  doc.addEventListener("visibilitychange", () => {
    if (doc.visibilityState === "hidden") releaseForBackground();
  });
  // iOS ignores user-scalable=no for pinch; touch-action covers the controls, this the rest.
  ["gesturestart", "gesturechange"].forEach(type => {
    doc.addEventListener(type, event => {
      if (touch.enabled) event.preventDefault();
    }, {passive: false});
  });
  // While the text input is focused a keyboard that resizes the layout viewport must not resize
  // the game window: only the height changes, and updateLift() keeps the field visible instead.
  // Registered before the bridges install their own resize listeners.
  let lastViewport = viewportSize();
  w.addEventListener("resize", event => {
    const view = viewportSize();
    const heightOnly = view.width === lastViewport.width && view.height !== lastViewport.height;
    if (touch.enabled && keyboardOpen() && heightOnly) {
      if (event && typeof event.stopImmediatePropagation === "function") event.stopImmediatePropagation();
      updateLift();
      return;
    }
    lastViewport = view;
    layoutKey = "";
    if (touch.enabled) sync();
  });
  // iOS can report the old size in the resize that follows a rotation; settle once more.
  w.addEventListener("orientationchange", () => {
    if (!touch.enabled) return;
    setTimeout(() => {
      lastViewport = viewportSize();
      layoutKey = "";
      sync();
    }, 300);
  });
  if (w.visualViewport && typeof w.visualViewport.addEventListener === "function") {
    w.visualViewport.addEventListener("resize", () => {
      if (touch.enabled) updateLift();
    });
  }

  touch.activate = () => activate("api");
  touch.deactivate = () => deactivate("api");
  touch.sync = sync;
  touch.openKeyboard = openKeyboard;
  touch.closeKeyboard = closeKeyboard;
  touch.layout = () => layout;
  touch.hotbar = () => hotbar;

  if (forced) activate("override");
  else if (touchPrimary()) activate("coarse-pointer");
})();
