export function isReloadDocumentReady(sample, previousToken) {
  return sample?.readyState === "complete" &&
    typeof sample.token === "string" && sample.token.length > 0 &&
    sample.token !== previousToken &&
    /TitleScreen|BrowserProfileScreen/.test(String(sample.screen || ""));
}
