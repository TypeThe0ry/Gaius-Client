#!/usr/bin/env bash
set -euo pipefail

python3_check='import sys; sys.exit(0 if sys.version_info.major == 3 else 1)'
windows=false
if [[ "${OSTYPE:-}" == msys || "${OSTYPE:-}" == cygwin || -n "${MSYSTEM:-}" ]]; then
  windows=true
  export PYTHON_MANAGER_AUTOMATIC_INSTALL=false
  unset PYLAUNCHER_ALLOW_INSTALL PYLAUNCHER_ALWAYS_INSTALL
fi

absolute_executable() {
  local path="$1"
  if [[ "$path" == [a-zA-Z]:* || "$path" == \\* ]]; then
    path="$(cygpath -au -- "$path")" || return 1
  elif [[ "$path" != */* ]]; then
    if ! path="$(type -P -- "$1")"; then
      "$windows" && path="$(type -P -- "$1.exe")" || return 1
    fi
  fi
  [[ "$path" == /* ]] || path="$PWD/$path"
  if "$windows" && [[ "$path" != *.exe && -x "$path.exe" ]]; then path="$path.exe"; fi
  [[ -f "$path" && -x "$path" ]] || return 1
  printf '%s\n' "$path"
}

resolve_python() {
  local candidate path interpreter
  if [[ -n "${PYTHON:-}" ]]; then
    path="$(absolute_executable "$PYTHON")" && "$path" -c "$python3_check" >/dev/null 2>&1 || return 1
    printf '%s\n%s\n' "${path##*/}" "$path"
    return
  fi

  # PATH may contain App Execution Aliases or a Python 2 installation.
  for candidate in python python3; do
    if path="$(absolute_executable "$candidate")" && "$path" -c "$python3_check" >/dev/null 2>&1; then
      printf '%s\n%s\n' "${path##*/}" "$path"
      return
    fi
  done

  if "$windows"; then
    # Resolve the real Python executable so later calls don't have to dispatch through py.
    if path="$(absolute_executable py)" && interpreter="$("$path" -3 -c \
      'import sys; sys.stdout.buffer.write(sys.executable.encode("utf-8"))' 2>/dev/null)" \
      && [[ -n "$interpreter" ]] && interpreter="$(absolute_executable "$interpreter")" \
      && "$interpreter" -c "$python3_check" >/dev/null 2>&1; then
      printf 'py -3\n%s\n' "$interpreter"
      return
    fi
  fi
  return 1
}

# The patcher calls --resolve once, then sets PYTHON for every staged script.
if [[ "${1:-}" == --resolve ]]; then
  if resolve_python; then exit 0; fi
elif [[ -n "${PYTHON:-}" ]]; then
  exec "$PYTHON" "$@"
elif resolved="$(resolve_python)"; then
  exec "${resolved#*$'\n'}" "$@"
fi

echo "A working Python 3 interpreter was not found" >&2
exit 127
