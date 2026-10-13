#!/usr/bin/env bash
# Serve the design prototypes over localhost for browser-based rendering.
#
# Why: the Playwright renderer used by the harness blocks file:// URLs, and the
# OpenDesign daemon is no longer part of the loop — design artifacts are
# rendered straight from the repository.
#
# Usage:  bash scripts/design_preview_server.sh [port]     # foreground
#         bash scripts/design_preview_server.sh 8765 &     # background
# Then:   http://127.0.0.1:<port>/field-shell.html
set -euo pipefail
PORT="${1:-8765}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO/design/rc-pilot-shell"
echo "serving $REPO/design/rc-pilot-shell at http://127.0.0.1:${PORT}/field-shell.html"
exec python3 -m http.server "$PORT" --bind 127.0.0.1
