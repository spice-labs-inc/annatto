#!/bin/bash
# Extracts metadata from a .crate (gzip tar) containing Cargo.toml
set -euo pipefail
CRATE_FILE="$1"
TMPDIR=$(mktemp -d)
tar xzf "$CRATE_FILE" -C "$TMPDIR"
CARGO_TOML=$(find "$TMPDIR" -name "Cargo.toml" -maxdepth 2 | head -1)
if [ -z "$CARGO_TOML" ]; then echo '{"error": "no Cargo.toml found"}'; exit 1; fi
cd "$(dirname "$CARGO_TOML")"
cargo metadata --manifest-path "$CARGO_TOML" --no-deps --format-version 1 2>/dev/null || cat "$CARGO_TOML"
rm -rf "$TMPDIR"
