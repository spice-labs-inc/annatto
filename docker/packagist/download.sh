#!/bin/bash
# Downloads Packagist packages from their dist URLs.
# Usage: download.sh vendor/package@version [vendor/package@version ...]
# Output: /work/out/vendor-package-version.zip
set -euo pipefail

for spec in "$@"; do
    vendor_package="${spec%%@*}"
    version="${spec##*@}"
    vendor="${vendor_package%%/*}"
    package="${vendor_package##*/}"
    outfile="/work/out/${vendor}-${package}-${version}.zip"

    if [ -f "$outfile" ]; then
        echo "SKIP $outfile (already exists)"
        continue
    fi

    echo "Fetching dist URL for ${vendor_package}@${version}..."
    api_url="https://repo.packagist.org/p2/${vendor}/${package}.json"
    dist_url=$(curl -sf "$api_url" | jq -r \
        --arg v "$version" \
        '.packages["'"${vendor}/${package}"'"][] | select(.version == $v) | .dist.url' \
        | head -1)

    if [ -z "$dist_url" ] || [ "$dist_url" = "null" ]; then
        echo "ERROR: Could not find dist URL for ${vendor_package}@${version}" >&2
        continue
    fi

    echo "Downloading $dist_url -> $outfile"
    curl -sfL -o "$outfile" "$dist_url"
    echo "OK $outfile"
done
