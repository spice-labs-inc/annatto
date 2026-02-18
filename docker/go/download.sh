#!/bin/sh
# Downloads Go module zips from proxy.golang.org.
# Usage: download.sh <module@version> [<module@version> ...]
# Example: download.sh github.com/gin-gonic/gin@v1.9.1
# Output: .zip files in /work/out/
set -eu
cd /work/out

# URL-encode module path per Go proxy convention: uppercase -> !lowercase
encode_module() {
    printf '%s' "$1" | sed 's/\([A-Z]\)/!\L\1/g'
}

for spec in "$@"; do
    module=$(printf '%s' "$spec" | sed 's/@[^@]*$//')
    version=$(printf '%s' "$spec" | sed 's/.*@//')
    encoded=$(encode_module "$module")
    safe_name=$(printf '%s' "$module" | tr '/' '_')
    url="https://proxy.golang.org/${encoded}/@v/${version}.zip"
    outfile="/work/out/${safe_name}@${version}.zip"
    echo "Downloading: $spec from $url" >&2
    wget -q -O "$outfile" "$url" || { echo "FAILED: $spec" >&2; continue; }
    echo "$outfile"
done
