// Inlined at the top of a site's index.html by build-pages-site.py, which also ships
// gaius-relay.html next to it.
//
// Reloading a cross-origin-isolated page in place keeps it in the same renderer process, and
// Chromium keeps much of the previous WebAssembly GC client's native memory there: 0.5-1 GB per
// reload, several GB after a few. A reload therefore hops through gaius-relay.html, which is
// served without Cross-Origin-Opener-Policy (the Service Worker leaves it alone): coming back
// from it puts the tab in a new browsing context group, so the page starts in a fresh process
// and the old one exits with that memory. The rest of this document is never parsed, so
// nothing starts before the hop. ?gaiusRelay=0 turns the hop off.
(function () {
  try {
    if (window.top !== window || window.crossOriginIsolated !== true) return;
    if (new URLSearchParams(location.search).get("gaiusRelay") === "0") return;
    var entry = performance.getEntriesByType && performance.getEntriesByType("navigation")[0];
    if (!entry || entry.type !== "reload") return;
    window.__gaiusReloadRelay = true;
    location.replace(new URL("gaius-relay.html", location.href).href + "#"
      + encodeURIComponent(location.href));
    document.write("<plaintext hidden>");
  } catch (_) {
    // Without the hop the page simply reloads in place.
  }
})();
