#!/bin/bash
# Downloads npm packages by name@version using npm pack inside Docker.
# Usage: download.sh <package-spec> [<package-spec> ...]
# Example: download.sh lodash@4.17.21 @babel/core@7.24.0
# Output: .tgz files in /work/out/
set -euo pipefail
cd /work/out
for spec in "$@"; do
    echo "Downloading: $spec" >&2
    npm pack "$spec" 2>/dev/null
done
ls -1 /work/out/*.tgz
