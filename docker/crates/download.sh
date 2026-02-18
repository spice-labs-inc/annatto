#!/bin/bash
# Downloads .crate files from crates.io static CDN.
# Usage: download.sh <name@version> [<name@version> ...]
# Example: download.sh serde@1.0.195 tokio@1.35.1
# Output: .crate files in /work/out/
set -euo pipefail
cd /work/out

for spec in "$@"; do
    name=$(printf '%s' "$spec" | sed 's/@[^@]*$//')
    version=$(printf '%s' "$spec" | sed 's/.*@//')
    url="https://static.crates.io/crates/${name}/${name}-${version}.crate"
    outfile="/work/out/${name}-${version}.crate"
    echo "Downloading: $spec from $url" >&2
    curl -fsSL -o "$outfile" "$url" || { echo "FAILED: $spec" >&2; continue; }
    echo "$outfile"
done
