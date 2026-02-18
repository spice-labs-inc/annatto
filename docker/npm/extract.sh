#!/bin/bash
# Extracts comprehensive metadata from an npm .tgz package using Node.js.
# Usage: extract.sh <package.tgz>
# Output: JSON to stdout
set -euo pipefail
PACKAGE_FILE="$1"
TMPDIR=$(mktemp -d)
tar xzf "$PACKAGE_FILE" -C "$TMPDIR"

# Find the package.json - it may be under package/ or under the package name
PKG_JSON=$(find "$TMPDIR" -maxdepth 2 -name package.json -type f | head -1)

if [ -z "$PKG_JSON" ]; then
    echo '{"error": "No package.json found"}' >&2
    exit 1
fi

node -e "
const fs = require('fs');
const pkg = JSON.parse(fs.readFileSync('${PKG_JSON}', 'utf8'));

// Extract author name from string or object format
function extractAuthorName(author) {
    if (!author) return null;
    if (typeof author === 'string') {
        // Format: 'Name <email> (url)'
        let name = author.trim();
        const angleIdx = name.indexOf('<');
        if (angleIdx > 0) name = name.substring(0, angleIdx).trim();
        const parenIdx = name.indexOf('(');
        if (parenIdx > 0) name = name.substring(0, parenIdx).trim();
        return name || null;
    }
    if (typeof author === 'object' && author.name) {
        return author.name;
    }
    return null;
}

// Extract publisher: author -> first maintainer -> first contributor
function extractPublisher() {
    const authorName = extractAuthorName(pkg.author);
    if (authorName) return authorName;
    if (Array.isArray(pkg.maintainers) && pkg.maintainers.length > 0) {
        return extractAuthorName(pkg.maintainers[0]);
    }
    if (Array.isArray(pkg.contributors) && pkg.contributors.length > 0) {
        return extractAuthorName(pkg.contributors[0]);
    }
    return null;
}

// Extract license handling all formats
function extractLicense() {
    if (pkg.license) {
        if (typeof pkg.license === 'string') return pkg.license;
        if (typeof pkg.license === 'object' && pkg.license.type) return pkg.license.type;
    }
    if (Array.isArray(pkg.licenses) && pkg.licenses.length > 0) {
        const types = pkg.licenses.map(l => {
            if (typeof l === 'string') return l;
            if (typeof l === 'object' && l.type) return l.type;
            return null;
        }).filter(Boolean);
        if (types.length > 0) return types.join(' OR ');
    }
    return null;
}

// Extract simple name (without scope)
function extractSimpleName(name) {
    if (!name) return null;
    if (name.startsWith('@') && name.includes('/')) {
        return name.substring(name.indexOf('/') + 1);
    }
    return name;
}

// Build dependencies with scopes
function extractDeps(field, scope) {
    const deps = pkg[field];
    if (!deps || typeof deps !== 'object') return [];
    return Object.entries(deps).map(([name, version]) => ({
        name: name,
        versionConstraint: typeof version === 'string' ? version.trim() || null : null,
        scope: scope
    }));
}

const result = {
    name: pkg.name || null,
    simpleName: extractSimpleName(pkg.name),
    version: pkg.version || null,
    description: pkg.description || null,
    license: extractLicense(),
    publisher: extractPublisher(),
    dependencies: [
        ...extractDeps('dependencies', 'runtime'),
        ...extractDeps('devDependencies', 'dev'),
        ...extractDeps('peerDependencies', 'peer'),
        ...extractDeps('optionalDependencies', 'optional')
    ]
};

console.log(JSON.stringify(result, null, 2));
"
rm -rf "$TMPDIR"
