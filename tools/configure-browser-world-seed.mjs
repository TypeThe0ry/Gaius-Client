import {mkdir, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

export const SEED_WIDGET_PREDICATE = "widget&&widget.visible!==false&&widget.active!==false"
  + "&&(String(widget.type||'').endsWith('CreateWorldScreen$WorldTab$1')"
  + "||(/EditBox$/.test(String(widget.type||''))&&/seed/i.test(String(widget.text||''))))";

export function seedScreenSupportsShortcut(state) {
  return /(?:^|[.$])CreateWorldScreen$/.test(String(state?.screen || ""));
}

export async function configureWorldSeed(session, seed, {findButton, click, dispatchKey, evaluate, waitFor, sleep, outputPath}) {
  seed = String(seed);
  if (!seed || seed.length > 32) throw new Error("world seed must contain 1 to 32 characters");
  let tab = await findButton(session, "World", 3_000);
  let usedWorldTabShortcut = false;
  let worldTabShortcutHeld = false;
  try {
    if (tab && tab.text.trim().toLowerCase() === "world") {
      await click(session, tab.x, tab.y);
    } else {
      const canUseWorldTabShortcut = await evaluate(session, "(() => {"
        + "const state=window.__gaiusMinecraftState||{};"
        + "return (" + seedScreenSupportsShortcut.toString() + ")(state);"
        + "})()");
      if (canUseWorldTabShortcut !== true) {
        throw new Error("--world-seed requires the visible World tab or the CreateWorldScreen");
      }
      usedWorldTabShortcut = true;
      await dispatchKey(session, "ControlLeft", "keyDown");
      worldTabShortcutHeld = true;
      await dispatchKey(session, "Digit2", "keyDown");
      await dispatchKey(session, "Digit2", "keyUp");
      await dispatchKey(session, "ControlLeft", "keyUp");
      worldTabShortcutHeld = false;
      await sleep(100);
    }
  const seedPredicate = SEED_WIDGET_PREDICATE;
  await waitFor(session, "(() => {const widgets=window.__gaiusMinecraftState?.screenWidgets||[];"
    + "return widgets.filter(widget=>" + seedPredicate + ").length===1;})()",
    5000, "the World tab seed input to replace the world-name input");
  const widget = await evaluate(session, "(() => {"
    + "const state=window.__gaiusMinecraftState||{};"
    + "const widgets=Array.isArray(state.screenWidgets)?state.screenWidgets:[];"
    + "const matches=widgets.filter(widget=>" + seedPredicate
    + "&&Number.isFinite(Number(widget.x))&&Number.isFinite(Number(widget.y))"
    + "&&Number.isFinite(Number(widget.width))&&Number.isFinite(Number(widget.height)));"
    + "return matches.length===1?matches[0]:{count:matches.length};"
    + "})()");
  if (!widget || widget.count !== undefined) {
    throw new Error("--world-seed requires exactly one visible active World EditBox; found "
      + String(widget?.count ?? 0));
  }
  const canvas = await evaluate(session, "(() => {"
    + "const canvas=document.querySelector('canvas');"
    + "const rect=canvas&&canvas.getBoundingClientRect();"
    + "const size=window.__gaiusMinecraftState?.screenSize;"
    + "return rect&&size&&Number(size.width)>0&&Number(size.height)>0"
    + "?{left:rect.left,top:rect.top,scaleX:rect.width/Number(size.width),scaleY:rect.height/Number(size.height)}:null;"
    + "})()");
  if (!canvas) throw new Error("--world-seed could not determine the canvas scale");
  const x = Math.round(canvas.left + (Number(widget.x) + Number(widget.width) / 2) * canvas.scaleX);
  const y = Math.round(canvas.top + (Number(widget.y) + Number(widget.height) / 2) * canvas.scaleY);
  const focusPredicate = "(() => {"
    + "const widgets=window.__gaiusMinecraftState?.screenWidgets||[];"
    + "const matches=widgets.filter(widget=>" + seedPredicate + ");"
    + "return matches.length===1&&matches[0].focused===true;"
    + "})()";
  const valuePredicate = "(() => {"
    + "const widgets=window.__gaiusMinecraftState?.screenWidgets||[];"
    + "const matches=widgets.filter(widget=>" + seedPredicate + ");"
    + "return matches.length===1&&matches[0].focused===true&&matches[0].value===" + JSON.stringify(seed) + ";"
    + "})()";
  const valueTelemetryPredicate = "(() => {"
    + "const widgets=window.__gaiusMinecraftState?.screenWidgets||[];"
    + "const matches=widgets.filter(widget=>" + seedPredicate + ");"
    + "return matches.length===1&&Object.prototype.hasOwnProperty.call(matches[0],'value');"
    + "})()";
  await session.send("Input.dispatchMouseEvent", {
    type: "mousePressed", x, y, button: "left", buttons: 1, clickCount: 1,
  });
  await session.send("Input.dispatchMouseEvent", {
    type: "mouseReleased", x, y, button: "left", buttons: 0, clickCount: 1,
  });
  await waitFor(session, focusPredicate, 3_000, "the World seed EditBox focus before input");
  // BrowserGlfw forwards DOM keydown characters to the canvas EditBox;
  // Input.insertText bypasses that bridge, while synthetic Ctrl+A can itself
  // be observed as an inserted "a".  Clear the 32-character input using End
  // plus bounded backspaces,
  // then send the requested seed through the same keydown/up path as a user.
  await session.send("Input.dispatchKeyEvent", {
    type: "keyDown", code: "End", key: "End", modifiers: 0,
    windowsVirtualKeyCode: 35, nativeVirtualKeyCode: 35,
  });
  await session.send("Input.dispatchKeyEvent", {
    type: "keyUp", code: "End", key: "End", modifiers: 0,
    windowsVirtualKeyCode: 35, nativeVirtualKeyCode: 35,
  });
  for (let index = 0; index < 32; index++) {
    await session.send("Input.dispatchKeyEvent", {
      type: "keyDown", code: "Backspace", key: "Backspace", modifiers: 0,
      windowsVirtualKeyCode: 8, nativeVirtualKeyCode: 8,
    });
    await session.send("Input.dispatchKeyEvent", {
      type: "keyUp", code: "Backspace", key: "Backspace", modifiers: 0,
      windowsVirtualKeyCode: 8, nativeVirtualKeyCode: 8,
    });
  }
  for (const character of Array.from(seed)) {
    const code = /^[a-z]$/i.test(character)
      ? "Key" + character.toUpperCase()
      : /^[0-9]$/.test(character) ? "Digit" + character : "Unidentified";
    await session.send("Input.dispatchKeyEvent", {
      type: "keyDown", code, key: character,
      text: character, unmodifiedText: character, modifiers: 0,
    });
    await session.send("Input.dispatchKeyEvent", {
      type: "keyUp", code, key: character, modifiers: 0,
    });
  }
  // The input bridge can update Java before UI telemetry observes focus.
  // Poll the same unique widget after input; do not re-click because that can
  // hide dropped seed characters behind a superficially focused control.
  const hasValueTelemetry = await evaluate(session, valueTelemetryPredicate);
  if (hasValueTelemetry === true) {
    await waitFor(session, valuePredicate, 3_000, "the World seed EditBox value after input");
  } else {
    await waitFor(session, focusPredicate, 3_000, "the World seed EditBox focus after input");
  }
  const finalWidget = await evaluate(session, "(() => {"
    + "const widgets=window.__gaiusMinecraftState?.screenWidgets||[];"
    + "const matches=widgets.filter(widget=>" + seedPredicate + ");"
    + "return matches.length===1?matches[0]:null;"
    + "})()");
  if (!finalWidget || (hasValueTelemetry === true && finalWidget.value !== seed)) {
    throw new Error(`--world-seed input value was not applied: ${JSON.stringify(finalWidget)}`);
  }
  await mkdir(resolve(outputPath, ".."), {recursive: true});
  let screenshotPath = outputPath + ".create-world-seed.png";
  try {
    const screenshot = await session.send("Page.captureScreenshot", {format: "png"});
    await writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));
  } catch (error) {
    screenshotPath = null;
  }
  return {
    configuredSeed: seed,
    effectiveSeed: null,
    acceptance: "unverified",
    widget: finalWidget,
    valueObserved: hasValueTelemetry === true,
    screenshotPath,
    usedWorldTabShortcut,
  };
  } finally {
    if (worldTabShortcutHeld) {
      await dispatchKey(session, "ControlLeft", "keyUp").catch(() => {});
    }
  }
}
