# Resolve the exact bridge generator sources omitted from the public snapshot.
# The caller passes the resulting array to javac alongside the regular sources.
gaius_local_bridge_sources() {
  local bundled_dir="$1" local_dir="${GAIUS_LOCAL_BRIDGE_GENERATORS:-}" name
  local -a required=(
    GaiusLightColumnAccessAsm.java
    BrowserUnihexLoaderAsm.java
    BrowserUnihexLoaderNested1Asm.java
    BrowserUnihexLoaderNested2Asm.java
    BrowserUnihexLoaderNestedBrowserGlyphAsm.java
    BrowserUnihexLoaderNestedByteLinesAsm.java
    BrowserUnihexLoaderNestedIntLinesAsm.java
    BrowserUnihexLoaderNestedShortLinesAsm.java
  )
  GAIUS_EXTRA_BRIDGE_SOURCES=()
  for name in "${required[@]}"; do
    [[ -f "$bundled_dir/$name" ]] && continue
    if [[ "$local_dir" != /* || ! -f "$local_dir/$name" ]]; then
      printf '%s\n' "Missing bridge generator $name. See patcher/recipes/README.md for local overlay inputs." >&2
      return 1
    fi
    GAIUS_EXTRA_BRIDGE_SOURCES+=("$local_dir/$name")
  done
}
