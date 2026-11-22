use std::{env, fs, io, path::PathBuf, process::Command};

const MANIFEST: &str = "gaius-builder.manifest";

fn main() {
    println!("cargo::rerun-if-changed={MANIFEST}");
    // A build script runs on the host, even when Cargo is cross-compiling.
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join(MANIFEST);
    match env::var("CARGO_CFG_TARGET_ENV").unwrap().as_str() {
        "msvc" => {
            link_arg("/MANIFEST:EMBED");
            link_arg(&format!("/MANIFESTINPUT:{}", manifest.to_str().unwrap()));
            // Suppress the linker's generated UAC fragment, not the one in our XML.
            link_arg("/MANIFESTUAC:NO");
        }
        "gnu" => embed_with_windres(manifest),
        other => panic!("cannot embed Windows manifest for target environment {other:?}"),
    }
}

fn link_arg(arg: &str) {
    println!("cargo::rustc-link-arg-bin=gaius-builder={arg}");
}

fn embed_with_windres(manifest: PathBuf) {
    println!("cargo::rerun-if-env-changed=WINDRES");
    println!("cargo::rerun-if-env-changed=PATH");
    let (prefix, format) = match env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "x86" => ("i686", "pe-i386"),
        "x86_64" => ("x86_64", "pe-x86-64"),
        // LLVM windres accepts a target triple in place of a BFD format.
        "aarch64" => ("aarch64", "aarch64-w64-mingw32"),
        other => panic!("cannot embed Windows manifest for GNU architecture {other:?}"),
    };

    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    // Keep the RC source independent of Windows path escaping and SDK headers.
    fs::copy(manifest, out.join(MANIFEST)).expect("copy Windows manifest to OUT_DIR");
    fs::write(out.join("manifest.rc"), format!("1 24 \"{MANIFEST}\"\n"))
        .expect("write RT_MANIFEST (24), executable resource ID 1");

    let tools = match env::var_os("WINDRES") {
        Some(tool) => vec![tool],
        None => vec![
            format!("{prefix}-w64-mingw32-windres").into(),
            "llvm-windres".into(),
            "windres".into(),
        ],
    };
    for tool in &tools {
        let result = Command::new(tool)
            .current_dir(&out)
            .args([
                "--target",
                format,
                "-O",
                "coff",
                "manifest.rc",
                "manifest.o",
            ])
            .status();
        match result {
            Ok(status) if status.success() => {
                link_arg(out.join("manifest.o").to_str().unwrap());
                return;
            }
            Ok(status) => panic!("{tool:?} failed to compile the Windows manifest: {status}"),
            Err(err) if err.kind() == io::ErrorKind::NotFound => continue,
            Err(err) => panic!("could not run Windows resource compiler {tool:?}: {err}"),
        }
    }
    panic!("no Windows resource compiler found (tried {tools:?}); set WINDRES to its full path");
}
