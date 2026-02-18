#!/bin/bash
# Downloads PyPI packages by name==version using pip inside Docker.
# Usage: download.sh <package-spec> [<package-spec> ...]
# Example: download.sh requests==2.31.0 Flask==3.0.0
# Output: package files in /work/out/
set -euo pipefail
cd /work/out
for spec in "$@"; do
    echo "Downloading: $spec" >&2
    pip download --no-deps "$spec" -d /work/out 2>/dev/null
done
ls -1 /work/out/*
