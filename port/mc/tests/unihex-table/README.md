# Unihex glyph table JVM gate

`node port/scripts/unihex-table-jvm-gate.mjs <scratch> [--profile 26.3] [--work <dir>] [--pack <vanilla-assets.pack.gz>]`

The browser reads the unifont zips of the vanilla pack from a build-time table
(`gaius-unihex.bin.gz`, `dev.gaius.tools.UnihexTableBuilder`, read by
`dev.gaius.browser.UnihexGlyphTable` and `BrowserUnihexLoader`) instead of inflating and
parsing them. This gate proves on the JVM that the fonts are unchanged:

1. `UnihexTableBuilder` writes the table from the font zips with vanilla's own
   `UnihexProvider$Definition.loadData` of the unpatched jar (vanilla is the oracle).
2. `UnihexGatePatcher` writes the real browser font patches (`patchUnihexProviderBrowserBulkParser`,
   `patchUnihexProviderBrowserAccess`, `patchFontProviderSelectionIndex`) of the same jar; the
   Gaius loader is compiled from the profile's staged sources (`VersionOps` reduced to the glyph
   upload of the generation's implementation, copied verbatim).
3. `UnihexGate` runs once per arm in its own JVM and writes a dump:
   - `providers`: every unihex provider of `include/unifont.json` and `include/unifont_pua.json`
     plus each zip without overrides and with synthetic size overrides (overlapping ranges,
     negative and wider-than-the-glyph dimensions, the ends of the signed byte); for each provider
     the supported set in its iteration order and, for every code point 0..U+10FFFF, the glyph,
     its identity per `getGlyph`, its info (advance with and without bold, shadow, bold offset as
     raw float bits) and its bake (stitched info, bitmap size, oversample, colour flag, bearings,
     and the `writeToTexture` arguments with the SHA-256 of the uploaded pixels, captured by a
     recording `GpuDevice`).
   - `fontsets`: vanilla `FontManager.prepare` on the pack, then per font (alt, default,
     illageralt, include/*, uniform) and per option set ({}, uniform, Japanese variants, both)
     a `FontSet` built as `FontManager.apply` builds it: active providers in order,
     `glyphsByWidth` (every width, the full code point list in order), 48 seeded
     `getRandomGlyph` draws per width (what §k obfuscation draws) and `source(false/true)` glyphs
     of ASCII, Latin, named scripts and a sample of every supported code point, each stitched
     bitmap recorded with its pixels.
4. Arms: `vanilla` (unpatched jar, no Gaius class); `table`; and the fallbacks `disabled`
   (`?gaiusUnihexLazy=0`), `sha-mismatch`, `length-mismatch`, `bad-header`, `not-archived` (the
   stream is not the vanilla pack's archive copy) and `user-pack` (a resource pack above vanilla
   ships the zips). `TableStub` stands in for gaius-boot.js (it hashes the bytes the pack serves,
   as the page hashes the archive entry). Every dump must equal the vanilla dump byte for byte;
   the table arm must take the table for every load, the fallback arms never.

The browser parts are covered by `node --test port/scripts/unihex-table-page.test.mjs` (the
page's table parse and archive verification, and the JS bodies of `UnihexGlyphTable`).

Known limit shared by both browser paths (before and after the table): the bulk parser patch
passes size overrides as `Dimensions.pack()`, so a resource pack override with `left` or `right`
outside -128..127 differs from vanilla (vanilla keeps the raw ints). The vanilla fonts have none.
