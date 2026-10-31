#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
port="${1:-8080}"

cat <<MSG
Gaius single-player launcher

Open this URL in a WebGPU-capable desktop browser:
  http://127.0.0.1:$port/port/web/singleplayer/

The launcher redirects to the checked-in Gaius browser distribution.
Press Ctrl-C to stop the server.
MSG

cd "$root"
exec python3 -m http.server "$port" --bind 127.0.0.1
