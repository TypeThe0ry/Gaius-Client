#!/usr/bin/env node
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const template = readFileSync(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");
const start = template.indexOf("      function retryGaiusStartup() {");
const end = template.indexOf("      if (retryButton) retryButton.addEventListener", start);
assert.ok(start >= 0 && end > start, "quit shell handler block must exist");
const handler = template.slice(start, end).replace("const gaiusQuitBarrierTimeoutMs = 45000;", "const gaiusQuitBarrierTimeoutMs = 35;");

function makePage(stop) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      id, hidden: true, textContent: "", disabled: false, dataset: {}, listeners: {},
      addEventListener(type, fn) { this.listeners[type] = fn; },
      click() { return this.listeners.click?.(); },
    });
    return elements.get(id);
  };
  const window = {closeCalls: 0, close() { this.closeCalls++; }, __gaiusTranslate(key) {
    return ({restarting: "Restarting", restart: "Restart", quitSaveFailedTitle: "Saving did not finish - keep this tab open",
      quitSaveFailedMessage: "Keep this tab open and retry saving.", retrySaving: "Retry saving"})[key] || key;
  }};
  const document = {documentElement: {dataset: {}}, getElementById: element};
  const location = {href: "http://localhost/index.html", replace(value) { this.replaced = value; }};
  const timers = [];
  const context = {
    window, document, location, URL, Promise, Error, String, Date,
    setStatus() {}, stopGaiusRuntimeForLeaseLoss: stop,
    finishGaiusPagehideTeardown() {},
    setTimeout(fn, ms) {
      if (ms === 5000) { const entry = {fn, ms, cleared: false}; timers.push(entry); return entry; }
      return setTimeout(fn, ms);
    },
    clearTimeout(timer) { if (timer && typeof timer === "object") timer.cleared = true; else clearTimeout(timer); },
  };
  context.globalThis = window;
  vm.createContext(context);
  const prelude = `
    const root=document.documentElement, bootScreen=document.getElementById("boot-screen"),
      profileGate=document.getElementById("profile-gate"), statusBox=document.getElementById("status"),
      errorActions=document.getElementById("gaius-error-actions"), retryButton=document.getElementById("gaius-retry"),
      detailsButton=document.getElementById("gaius-error-toggle"), detailsBox=document.getElementById("gaius-error-details"),
      stoppedScreen=document.getElementById("gaius-stopped"), stoppedRestart=document.getElementById("gaius-stopped-restart"),
      quitWarning=document.getElementById("gaius-quit-warning"), quitWarningReason=document.getElementById("gaius-quit-reason"),
      quitRetry=document.getElementById("gaius-quit-retry"), quitRestart=document.getElementById("gaius-quit-restart");
    function renderShell() {}
  `;
  vm.runInContext(`(function(){${prelude}\n${handler}\n})()`, context);
  element("gaius-quit-warning-title").textContent = window.__gaiusTranslate("quitSaveFailedTitle");
  element("gaius-quit-warning-message").textContent = window.__gaiusTranslate("quitSaveFailedMessage");
  element("gaius-quit-retry").textContent = window.__gaiusTranslate("retrySaving");
  element("gaius-quit-retry").addEventListener("click", () => window.__gaiusRequestQuit());
  return {window, document, elements, timers, location};
}

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

// Confirmed Worker storage close + stop resolves to the only state that claims the tab is safe.
{
  const page = makePage(async () => {});
  await page.window.__gaiusRequestQuit();
  await wait(140);
  assert.equal(page.window.__gaiusQuitComplete, true);
  assert.equal(page.window.__gaiusQuitSafe, true);
  assert.equal(page.window.__gaiusStopped, true);
  assert.equal(page.elements.get("gaius-stopped").hidden, false);
  assert.equal(page.elements.get("gaius-quit-warning").hidden, true);
  assert.equal(page.window.closeCalls, 0);
}

// A storage or Worker failure never displays the safe page; retry can subsequently succeed.
{
  let attempts = 0;
  const page = makePage(async () => {
    if (++attempts === 1) throw new Error("Worker did not confirm storage close");
  });
  await page.window.__gaiusRequestQuit();
  assert.equal(page.window.__gaiusQuitSafe, false);
  assert.equal(page.window.__gaiusStopped, false);
  assert.equal(page.elements.get("gaius-stopped").hidden, true);
  assert.equal(page.elements.get("gaius-quit-warning").hidden, false);
  assert.match(page.elements.get("gaius-quit-warning-title").textContent, /keep this tab open/);
  assert.match(page.elements.get("gaius-quit-reason").textContent, /storage close/);
  await page.elements.get("gaius-quit-retry").click();
  await wait(140);
  assert.equal(attempts, 2);
  assert.equal(page.window.__gaiusQuitSafe, true);
  assert.equal(page.window.closeCalls, 0);
}

// A hung Worker is bounded, leaves the warning visible, and schedules another flush attempt.
{
  let attempts = 0;
  const page = makePage(() => { attempts++; return new Promise(() => {}); });
  await page.window.__gaiusRequestQuit();
  await wait(60);
  assert.equal(attempts, 1);
  assert.equal(page.window.__gaiusQuitSafe, false);
  assert.equal(page.window.__gaiusStopped, false);
  assert.equal(page.elements.get("gaius-quit-warning").hidden, false);
  assert.match(page.elements.get("gaius-quit-reason").textContent, /timed out/);
  assert.ok(page.timers.some(timer => timer.ms === 5000 && !timer.cleared), "background retry is scheduled");
  await page.elements.get("gaius-quit-retry").click();
  await wait(60);
  assert.equal(attempts, 2, "manual retry starts a fresh bounded attempt");
  assert.equal(page.window.closeCalls, 0);
  assert.equal(page.window.__gaiusQuitSafe, false);
}

console.log("quit barrier success/failure/timeout smoke passed");
