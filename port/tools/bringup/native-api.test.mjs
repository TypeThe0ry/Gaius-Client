import assert from "node:assert/strict";
import {mkdirSync, mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test, {after} from "node:test";
import {apiLockTargets, nativeEntryDiff} from "./gaius-bringup.mjs";

// GAIUS_BRINGUP_TEST_OUT names a disposable output directory; without it the tests use a
// private temporary directory that is removed afterwards.
const ownsOut = !process.env.GAIUS_BRINGUP_TEST_OUT;
const out = process.env.GAIUS_BRINGUP_TEST_OUT || mkdtempSync(join(tmpdir(), "gaius-bringup-test-"));
mkdirSync(out, {recursive: true});
after(() => {
    if (ownsOut) rmSync(out, {recursive: true, force: true});
});

test("native entry diff uses every ExtRefs library section and full descriptors", () => {
    const before = "[jdk] 1\nM java/lang/System.nanoTime()J\n[library org.lwjgl:lwjgl-sdl:3.4.2] 1\nM org/lwjgl/sdl/SDLVideo.SDL_CreateWindow()J\n[library com.example:binding:1] 1\nM binding/Api.call(I)V\n";
    const after = before.replace("3.4.2", "3.4.3").replace("call(I)V", "call(J)V");
    const diff = nativeEntryDiff(before, after, ["org/lwjgl/sdl/SDLVideo.SDL_CreateWindow"]);
    assert.deepEqual(diff.added, ["com.example:binding binding/Api.call(J)V"]);
    assert.deepEqual(diff.removed, ["com.example:binding binding/Api.call(I)V"]);
    assert.equal(diff.unchanged, 1);
    assert.equal(diff.count, 2);
    assert.deepEqual(diff.missingRequired, []);
    assert.deepEqual(diff.libraries, ["com.example:binding", "org.lwjgl:lwjgl-sdl"]);
});

test("only explicitly declared required native entries are missingRequired", () => {
    const before = "[library org.lwjgl:lwjgl-sdl:1]\nM org/lwjgl/sdl/SDLVideo.SDL_CreateWindow()J\nM org/lwjgl/sdl/SDLVideo.SDL_Optional()V\n";
    const after = "[library org.lwjgl:lwjgl-sdl:2]\nM org/lwjgl/sdl/SDLVideo.SDL_NewFeature()V\n";
    assert.deepEqual(nativeEntryDiff(before, after).missingRequired, []);
    assert.deepEqual(nativeEntryDiff(before, after, ["org/lwjgl/sdl/SDLVideo.SDL_CreateWindow"]).missingRequired,
        ["org/lwjgl/sdl/SDLVideo.SDL_CreateWindow"]);
});

test("api lock targets resolve a new library version independently of the lock range", () => {
    const root = mkdtempSync(join(out, "api-lock-test-"));
    const directory = join(root, "port/overrides/libraries/binding/src/versions/1.0");
    mkdirSync(directory, {recursive: true});
    writeFileSync(join(directory, "api.lock"), "library com.example:binding\nrange 1.0\n");
    const targets = apiLockTargets(root, [{name: "com.example:binding:2.0:natives-windows", path: "native.jar"},
        {name: "com.example:binding:2.0", path: "binding.jar"}]);
    assert.equal(targets[0].library, "com.example:binding:2.0");
    assert.equal(targets[0].jar, "binding.jar");
    assert.deepEqual(apiLockTargets(root, [])[0], {lock: join(directory, "api.lock"), coordinate: "com.example:binding", library: null, jar: null});
});
