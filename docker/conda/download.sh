#!/usr/bin/env bash
# Downloads real Conda packages from conda-forge.
# Usage: download.sh <subdir>/<filename> [<subdir>/<filename> ...]
# Example: download.sh linux-64/numpy-1.26.4-py312hc5e2394_0.conda
set -euo pipefail

CHANNEL_URL="https://conda.anaconda.org/conda-forge"
OUTDIR="/work/out"

for spec in "$@"; do
    subdir="${spec%%/*}"
    filename="${spec#*/}"
    outpath="${OUTDIR}/${filename}"

    if [ -f "$outpath" ]; then
        echo "SKIP (exists): $filename"
        continue
    fi

    url="${CHANNEL_URL}/${subdir}/${filename}"
    echo "Downloading: $url"
    if curl -fsSL -o "$outpath" "$url"; then
        echo "OK: $filename"
    else
        echo "FAIL: $filename (from $url)" >&2
    fi
done
