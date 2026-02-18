#!/bin/bash
# Downloads .gem files from rubygems.org.
# Usage: download.sh <name@version> [<name@version> ...]
# Example: download.sh rake@13.1.0 rails@7.1.3
# Output: .gem files in /work/out/
set -euo pipefail
cd /work/out

for spec in "$@"; do
    name=$(printf '%s' "$spec" | sed 's/@[^@]*$//')
    version=$(printf '%s' "$spec" | sed 's/.*@//')
    url="https://rubygems.org/downloads/${name}-${version}.gem"
    outfile="/work/out/${name}-${version}.gem"
    echo "Downloading: $spec from $url" >&2
    curl -fsSL -o "$outfile" "$url" || { echo "FAILED: $spec" >&2; continue; }
    echo "$outfile"
done
