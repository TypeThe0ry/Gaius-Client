package dev.gaius.browser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/** Browser file picker and drag/drop bridge for the vanilla resource-pack screen. */
public final class BrowserUserResourcePacks {
    private static final String IMPORT_ROOT = "/gaius/resourcepack-imports/";
    private static final Set<String> PENDING_ROOTS = new HashSet<>();
    private static PackSelectionScreen activeScreen;
    private static boolean batchPending;

    private BrowserUserResourcePacks() {
    }

    public static void install(PackSelectionScreen screen) {
        activeScreen = screen;
        installPicker(BrowserUserResourcePacks::acceptFile, BrowserUserResourcePacks::finishBatch);
    }

    /** Called from the patched screen class, which is allowed to call Screen's protected API. */
    public static Button createButton(PackSelectionScreen screen) {
        return Button.builder(
                        BrowserI18n.text("resource_pack.add"),
                        ignored -> beginPick())
                .bounds(Math.max(4, screen.width / 2 - 100), Math.max(4, screen.height - 60), 200, 20)
                .build();
    }

    public static void tick(PackSelectionScreen screen) {
        if (activeScreen != screen || !batchPending || PENDING_ROOTS.isEmpty()) {
            return;
        }
        List<Path> roots = new ArrayList<>(PENDING_ROOTS.size());
        for (String root : PENDING_ROOTS) {
            roots.add(Path.of(IMPORT_ROOT + root));
        }
        PENDING_ROOTS.clear();
        batchPending = false;
        screen.onFilesDrop(roots);
        report("resource-pack-imported", Integer.toString(roots.size()));
    }

    public static void close(PackSelectionScreen screen) {
        if (activeScreen == screen) {
            activeScreen = null;
            PENDING_ROOTS.clear();
            batchPending = false;
            removePicker();
        }
    }

    private static void beginPick() {
        if (activeScreen != null) {
            openPicker();
        }
    }

    private static void acceptFile(String relativePath, Int8Array bytes) {
        if (activeScreen == null || relativePath == null || bytes == null) {
            return;
        }
        byte[] copy = bytes.copyToJavaArray();
        if (copy == null || copy.length == 0 || copy.length > 256 * 1024 * 1024) {
            report("resource-pack-rejected", "size");
            return;
        }
        String relative = safeRelativePath(relativePath);
        if (relative == null) {
            report("resource-pack-rejected", "path");
            return;
        }
        if (!BrowserFilePersistence.installUserResourcePack(relative, copy)) {
            report("resource-pack-rejected", relative);
            return;
        }
        int slash = relative.indexOf('/');
        String root = slash < 0 ? relative : relative.substring(0, slash);
        PENDING_ROOTS.add(root);
    }

    private static void finishBatch() {
        if (activeScreen != null && !PENDING_ROOTS.isEmpty()) {
            batchPending = true;
        }
    }

    private static String safeRelativePath(String value) {
        String path = value.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.indexOf('\0') >= 0) {
                return null;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(part);
        }
        return result.length() == 0 ? null : result.toString();
    }

    @JSFunctor
    private interface FileCallback extends JSObject {
        void accept(String path, Int8Array bytes);
    }

    @JSFunctor
    private interface BatchCallback extends JSObject {
        void run();
    }

    @JSBody(params = {"fileCallback", "batchCallback"}, script = """
            globalThis.__gaiusUserResourcePacks = globalThis.__gaiusUserResourcePacks || {};
            const state = globalThis.__gaiusUserResourcePacks;
            state.fileCallback = fileCallback;
            state.batchCallback = batchCallback;
            state.active = true;
            if (state.installed) return;
            state.installed = true;
            state.read = function(file, relative) {
              if (!file || file.size <= 0 || file.size > 256 * 1024 * 1024) {
                return Promise.reject(new Error('resource pack file size is outside the supported range'));
              }
              return file.arrayBuffer().then(function(buffer) {
                var bytes = new Uint8Array(buffer);
                state.fileCallback(relative || file.name, new Int8Array(bytes.buffer));
              });
            };
            // Named factories instead of immediately-invoked function expressions: the TeaVM
            // JSBody minifier drops the wrapping parentheses of an IIFE, which the WasmGC
            // runtime then rejects as an anonymous function statement.
            state.walkTask = function(entry, prefix) {
              return function() { return state.walk(entry, prefix); };
            };
            state.readTask = function(file) {
              return function() { return state.read(file, file.webkitRelativePath || file.name); };
            };
            state.walk = function(entry, prefix) {
              if (entry.isFile) {
                return new Promise(function(resolve, reject) {
                  entry.file(function(file) {
                    state.read(file, prefix + file.name).then(resolve, reject);
                  }, reject);
                });
              }
              if (!entry.isDirectory) return Promise.resolve();
              var reader = entry.createReader();
              var nextBatch = function() {
                return new Promise(function(resolve, reject) {
                  reader.readEntries(resolve, reject);
                }).then(function(entries) {
                  if (!entries.length) return null;
                  var chain = Promise.resolve();
                  for (var i = 0; i < entries.length; i++) {
                    chain = chain.then(state.walkTask(entries[i], prefix + entry.name + '/'));
                  }
                  return chain.then(nextBatch);
                });
              };
              return nextBatch();
            };
            state.dispatch = function(files) {
              var chain = Promise.resolve();
              for (var i = 0; i < files.length; i++) {
                chain = chain.then(state.readTask(files[i]));
              }
              return chain.catch(function(error) {
                try { console.warn('Could not import resource pack', error); } catch (_) {}
              }).then(function() { state.batchCallback(); });
            };
            state.drop = function(event) {
              if (!state.active) return;
              event.preventDefault();
              var files = [];
              var walked = false;
              var chain = Promise.resolve();
              var transfer = event.dataTransfer;
              var items = event.dataTransfer && event.dataTransfer.items;
              if (items && items.length) {
                for (var i = 0; i < items.length; i++) {
                  var item = items[i];
                  var entry = item.webkitGetAsEntry && item.webkitGetAsEntry();
                  if (entry) {
                    walked = true;
                    chain = chain.then(state.walkTask(entry, ''));
                  } else {
                    var file = item.getAsFile && item.getAsFile();
                    if (file) files.push(file);
                  }
                }
              } else if (transfer && transfer.files) {
                for (var j = 0; j < transfer.files.length; j++) files.push(transfer.files[j]);
              }
              chain.catch(function(error) {
                try { console.warn('Could not import resource pack', error); } catch (_) {}
              }).then(function() {
                if (files.length) return state.dispatch(files);
                if (walked) state.batchCallback();
              });
            };
            state.over = function(event) { if (state.active) event.preventDefault(); };
            document.addEventListener('drop', state.drop, true);
            document.addEventListener('dragover', state.over, true);
            """)
    private static native void installPicker(FileCallback fileCallback, BatchCallback batchCallback);

    @JSBody(script = """
            const state = globalThis.__gaiusUserResourcePacks;
            if (!state) return;
            state.active = true;
            const input = document.createElement('input');
            input.type = 'file';
            input.multiple = true;
            input.accept = '.zip,application/zip,application/x-zip-compressed';
            input.hidden = true;
            input.addEventListener('change', function() {
              var files = input.files ? Array.prototype.slice.call(input.files) : [];
              input.remove();
              state.dispatch(files);
            }, {once: true});
            document.body.appendChild(input);
            input.click();
            """)
    private static native void openPicker();

    @JSBody(script = """
            const state = globalThis.__gaiusUserResourcePacks;
            if (!state) return;
            state.active = false;
            """)
    private static native void removePicker();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({event: 'resource-pack-' + event, detail: String(detail || ''), at: Date.now()});
              if (events.length > 500) events.splice(0, events.length - 500);
            } catch (_) {}
            """)
    private static native void report(String event, String detail);
}
