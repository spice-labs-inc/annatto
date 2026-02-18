#!/bin/bash
# Extracts metadata from a .crate (gzip tar) containing Cargo.toml.
# Uses cargo read-manifest for structured metadata, then transforms via jq.
# Usage: extract.sh <file.crate>
# Output: JSON to stdout matching Annatto schema:
#   {name, simpleName, version, description, license, publisher, dependencies}
set -euo pipefail
CRATE_FILE="$1"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

tar xzf "$CRATE_FILE" -C "$TMPDIR"

# Find Cargo.toml (at depth 2: <name>-<version>/Cargo.toml)
CARGO_TOML=$(find "$TMPDIR" -name "Cargo.toml" -maxdepth 2 | head -1)
if [ -z "$CARGO_TOML" ]; then
    echo '{"error": "no Cargo.toml found"}' >&2
    exit 1
fi

MANIFEST_DIR=$(dirname "$CARGO_TOML")
cd "$MANIFEST_DIR"

# cargo read-manifest works offline on extracted crates
MANIFEST_JSON=$(cargo read-manifest --manifest-path "$CARGO_TOML" 2>/dev/null)

# Transform to Annatto JSON schema using jq
printf '%s' "$MANIFEST_JSON" | jq '
{
    name: .name,
    simpleName: .name,
    version: .version,
    description: (if .description then .description else null end),
    license: (if .license then .license else null end),
    publisher: (
        if (.authors | length) > 0 then
            .authors[0] | gsub("\\s*<[^>]*>\\s*$"; "")
        else
            null
        end
    ),
    dependencies: [
        .dependencies[] |
        {
            name: .name,
            versionConstraint: (if .req and .req != "*" then .req else null end),
            scope: (
                if .kind == "dev" then "dev"
                elif .kind == "build" then "build"
                else "runtime"
                end
            )
        }
    ]
}
'
