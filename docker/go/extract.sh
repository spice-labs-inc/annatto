#!/bin/sh
# Extracts metadata from a Go module .zip archive and outputs Annatto source-of-truth JSON.
# Usage: extract.sh <module.zip>
# Output: JSON to stdout matching {name, simpleName, version, description, license, publisher, dependencies}
set -eu
MODULE_ZIP="$1"
TMPDIR=$(mktemp -d)
unzip -q "$MODULE_ZIP" -d "$TMPDIR"

# Find go.mod (at module root inside the zip — paths like github.com/user/repo@version/go.mod)
GOMOD=$(find "$TMPDIR" -name "go.mod" -maxdepth 6 | head -1)
if [ -z "$GOMOD" ]; then
    echo '{"error": "no go.mod found"}' >&2
    exit 1
fi

# Extract version from zip entry path: module@version/go.mod -> version
GOMOD_DIR=$(dirname "$GOMOD")
ENTRY_NAME=$(basename "$GOMOD_DIR")
VERSION=$(printf '%s' "$ENTRY_NAME" | sed 's/.*@//')

# Run go mod edit -json for structured go.mod data
cd "$GOMOD_DIR"
GO_MOD_JSON=$(go mod edit -json)

# Transform to Annatto JSON schema using jq
printf '%s' "$GO_MOD_JSON" | jq --arg version "$VERSION" '
{
    name: .Module.Path,
    simpleName: (
        .Module.Path | split("/") | last |
        if test("^v[0-9]+$") then . else . end
    ),
    version: $version,
    description: null,
    license: null,
    publisher: null,
    dependencies: (
        if .Require then
            [.Require[] | {
                name: .Path,
                versionConstraint: .Version,
                scope: (if .Indirect then "indirect" else "runtime" end)
            }]
        else
            []
        end
    )
}
'

rm -rf "$TMPDIR"
