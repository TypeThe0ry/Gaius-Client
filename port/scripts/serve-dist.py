#!/usr/bin/env python3
"""Serve the browser dist with precompressed asset support.

Python's built-in http.server is convenient, but it ignores classes.js.gz/br.
For a supported client that means repeatedly transferring a very large JS file
when testing locally or when a simple static host is used. This handler serves
*.br or *.gz variants when the browser advertises support and the compressed
file exists, while still falling back to the plain file.
"""

from __future__ import annotations

import argparse
import os
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


class PrecompressedHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):  # noqa: A002 - inherited API name
        try:
            super().log_message(format, *args)
        except (AttributeError, BrokenPipeError, OSError, ValueError):
            # A detached terminal must not turn a live static server into empty HTTP replies.
            pass

    def handle(self):  # noqa: D401 - inherited API hook
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            self.log_message("client disconnected before transfer completed")

    def send_head(self):  # noqa: N802 - inherited API name
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()

        accept = self.headers.get("Accept-Encoding", "")
        for encoding, suffix in (("br", ".br"), ("gzip", ".gz")):
            if encoding not in accept:
                continue
            compressed_path = path + suffix
            if not os.path.isfile(compressed_path):
                continue
            # Do not let an interrupted release build serve an older client payload.
            # gzip commonly preserves the source timestamp. Filesystem timestamp
            # conversion can make that sidecar appear a few nanoseconds older
            # than its source even when it was built from it, so only reject a
            # sidecar that is materially older.
            if os.path.getmtime(compressed_path) + 1.0 < os.path.getmtime(path):
                continue

            file_handle = open(compressed_path, "rb")
            stat = os.fstat(file_handle.fileno())
            self.send_response(200)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Encoding", encoding)
            self.send_header("Vary", "Accept-Encoding")
            self.send_header("Content-Length", str(stat.st_size))
            self.send_header("Last-Modified", self.date_time_string(os.path.getmtime(path)))
            self.end_headers()
            self.log_message(
                "serving precompressed %s as %s (%d bytes)",
                os.path.basename(path),
                encoding,
                stat.st_size,
            )
            return file_handle

        return super().send_head()

    def end_headers(self):  # noqa: N802 - inherited API name
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        cache_control = self.cache_control()
        self.send_header("Cache-Control", cache_control)
        if "no-store" in cache_control:
            self.send_header("Pragma", "no-cache")
            self.send_header("Expires", "0")
        super().end_headers()

    def cache_control(self) -> str:
        parsed = urlsplit(self.path)
        name = os.path.basename(parsed.path)
        query = parse_qs(parsed.query)
        versioned_assets = {
            "classes.js",
            "singleplayer-server.js",
            "singleplayer-server-worker.js",
            "vanilla-assets.pack.gz",
        }
        if name in versioned_assets and query.get("v"):
            return "public, max-age=31536000, immutable"
        if name in versioned_assets:
            return "public, max-age=0, must-revalidate"
        return "no-store, no-cache, must-revalidate, max-age=0"


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--directory", default=str(root / "port" / "web"))
    args = parser.parse_args()

    handler = partial(PrecompressedHandler, directory=args.directory)
    server = ThreadingHTTPServer((args.host, args.port), handler)
    print(f"Serving {args.directory} at http://{args.host}:{args.port}/")
    server.serve_forever()


if __name__ == "__main__":
    main()
