#!/usr/bin/env bash
# Downloads real CPAN distribution tarballs from MetaCPAN.
# Usage: download.sh <author/Dist-Name-Version.tar.gz> [...]
# Example: download.sh ETHER/Moose-2.2207.tar.gz
set -euo pipefail

CPAN_BASE="https://cpan.metacpan.org/authors/id"
OUTDIR="/work/out"

for spec in "$@"; do
    # spec is like ETHER/Moose-2.2207.tar.gz
    author="${spec%%/*}"
    filename="${spec#*/}"
    outpath="${OUTDIR}/${filename}"

    if [ -f "$outpath" ]; then
        echo "SKIP (exists): $filename"
        continue
    fi

    # Build CPAN path: E/ET/ETHER/Dist-Ver.tar.gz
    first="${author:0:1}"
    second="${author:0:2}"
    url="${CPAN_BASE}/${first}/${second}/${author}/${filename}"

    echo "Downloading: $url"
    if curl -fsSL -o "$outpath" "$url"; then
        echo "OK: $filename"
    else
        echo "FAIL: $filename (from $url)" >&2
    fi
done
