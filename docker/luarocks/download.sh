#!/usr/bin/env bash
# Downloads real LuaRocks source rock packages from luarocks.org.
# Usage: download.sh <name> <version> [<name> <version> ...]
# Example: download.sh luasocket 3.1.0-1 luafilesystem 1.8.0-1
set -euo pipefail

OUTDIR="/work/out"

while [ $# -ge 2 ]; do
    name="$1"
    version="$2"
    shift 2

    filename="${name}-${version}.src.rock"
    outpath="${OUTDIR}/${filename}"

    if [ -f "$outpath" ]; then
        echo "SKIP (exists): $filename"
        continue
    fi

    # Try downloading with luarocks download --source
    echo "Downloading: $name $version"
    cd /tmp
    if luarocks download --source "$name" "$version" 2>/dev/null; then
        # Find the downloaded file (could be .src.rock or .rockspec)
        downloaded=$(ls -1 "${name}-${version}"*.src.rock 2>/dev/null | head -1 || true)
        if [ -n "$downloaded" ] && [ -f "$downloaded" ]; then
            mv "$downloaded" "$outpath"
            echo "OK: $filename"
        else
            # Try rockspec fallback
            downloaded=$(ls -1 "${name}-${version}"*.rockspec 2>/dev/null | head -1 || true)
            if [ -n "$downloaded" ] && [ -f "$downloaded" ]; then
                mv "$downloaded" "${OUTDIR}/${name}-${version}.rockspec"
                echo "OK (rockspec): ${name}-${version}.rockspec"
            else
                echo "FAIL: $name $version (no file found after download)" >&2
            fi
        fi
        # Clean up any leftover files
        rm -f "${name}-${version}"*.src.rock "${name}-${version}"*.rockspec 2>/dev/null || true
    else
        # Direct URL fallback
        url="https://luarocks.org/manifests/${name}/${name}-${version}.src.rock"
        echo "Trying direct URL: $url"
        if curl -fsSL -o "$outpath" "$url"; then
            echo "OK (direct): $filename"
        else
            echo "FAIL: $name $version" >&2
        fi
    fi
done
