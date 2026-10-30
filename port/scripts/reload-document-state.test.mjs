import assert from "node:assert/strict";
import {isReloadDocumentReady} from "./reload-document-state.mjs";

const previous = {readyState: "complete", token: "before", screen: "TitleScreen"};
assert.equal(isReloadDocumentReady(previous, "before"), false, "old document is not a reload");
assert.equal(isReloadDocumentReady({...previous, token: "after", readyState: "loading"}, "before"), false,
  "intermediate document is not ready");
assert.equal(isReloadDocumentReady({...previous, token: "after", screen: "about:blank"}, "before"), false,
  "new document without the game title is not ready");
assert.equal(isReloadDocumentReady({...previous, token: "after"}, "before"), true,
  "new complete title document is ready");
console.log("reload document state smoke passed");
