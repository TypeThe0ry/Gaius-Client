# JDK API gaps

Java SE API that vanilla Minecraft (or a library it ships) calls and TeaVM's class library does
not implement, keyed by Java release. The TeaVM runtime itself (threads,
JSPI, the browser file system, buffers, JavaScript interop, fixes of TeaVM's own classes) stays in
`port/overrides/classlib`.

## Layout and selection

```
port/overrides/jdk/<release>/src/main/java/            both TeaVM targets
port/overrides/jdk/<release>/src/target-<target>/java/  one target (js or wasmgc); replaces the
                                                        same relative path of src/main/java
```

A profile selects every `<release>` up to its `javaVersion` (`port/versions/<id>.json`), in
ascending order, because a Java release keeps the API of the releases before it: 1.21.11
(Java 21) compiles `21/`, the 26.x profiles (Java 25) compile `21/` and `25/`. A file of a later
release replaces the same relative path of an earlier one. `build-overlays.sh` compiles the
selected roots together with the classlib overlay into
`teavm-classlib-<version>-gaius.jar`; a path may not exist both here and in the classlib
overlay.

Put a substitute in the oldest release whose API has the member: `21/` holds everything up to
Java 21, `25/` what Java 22 to 25 added. Classes follow TeaVM's naming
(`org/teavm/classlib/java/**/T<Name>`, which TeaVM links as `java/**/<Name>`); the
`*ModernSupport` classes are the targets of the delegate methods `TeaVMClasslibPatcher` adds to
TeaVM's own classes.

## Finding the gaps

```
bash port/tools/extrefs/extrefs.sh jdk <profile> [--patched <overlays>/client-named-<profile>-gaius.jar]
```

lists every JDK member and class that vanilla references and the class library (with these
roots) does not provide, with the `jdk/<release>` it belongs in. `port/mc/profiles/<id>/usage.lock`
records the JDK surface each profile uses (`extrefs.sh usage <id> --check` reports a change).
A new Minecraft version on a known Java release usually needs nothing here; a new Java release
adds a `<release>/` directory.

The Java 22-25 API that the 26.x clients use (`java.lang.ScopedValue`, `Reader.readAllAsString()`,
`Path.resolve(String, String...)`, and on 26.3 `Math.powExact(int, int)`) has no substitute here
yet: the client patchers rewrite those call sites (`Minecraft262BrowserPatcher`, with its storage
patches, on 26.2; `JdkCompatPatches` with the patch ids `JdkCompatPatches263.*` on 26.3, which
redirects them to the helpers `BrowserJdkCompat` and `BrowserScopedValue`, kept in `port/src` and
the `renderpearl-1` source set of `port/mc/shared`). They move to `25/` together with those
patches.
