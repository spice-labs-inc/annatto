#!/usr/bin/env python3
"""
Extracts metadata from Conda .tar.bz2 or .conda packages and outputs
the Annatto source-of-truth JSON schema.

Usage: extract.py <package-file> [<package-file> ...]
Output: JSON to stdout for each package.

When run with --batch, outputs one JSON per file to /work/out/<basename>-expected.json.
"""
import json
import os
import sys
import tarfile
import zipfile
from datetime import datetime, timezone


def read_info_from_tar(tar):
    """Read info/index.json and info/about.json from an open tarfile."""
    index_json = None
    about_json = None
    for member in tar.getmembers():
        name = member.name
        # Normalize ./info/ to info/
        if name.startswith('./'):
            name = name[2:]
        if name == 'info/index.json':
            f = tar.extractfile(member)
            if f:
                index_json = json.loads(f.read())
        elif name == 'info/about.json':
            f = tar.extractfile(member)
            if f:
                about_json = json.loads(f.read())
    return index_json, about_json


def extract_from_conda(path):
    """Extract from modern .conda format (ZIP with info-*.tar.zst inside)."""
    import zstandard as zstd
    import io

    with zipfile.ZipFile(path) as zf:
        for entry_name in zf.namelist():
            if entry_name.startswith('info-') and entry_name.endswith('.tar.zst'):
                with zf.open(entry_name) as zst_stream:
                    dctx = zstd.ZstdDecompressor()
                    decompressed = dctx.stream_reader(zst_stream)
                    with tarfile.open(fileobj=io.BytesIO(decompressed.read())) as tar:
                        return read_info_from_tar(tar)
    return None, None


def extract_from_tar_bz2(path):
    """Extract from legacy .tar.bz2 format."""
    with tarfile.open(path, 'r:bz2') as tar:
        return read_info_from_tar(tar)


def parse_match_spec(spec):
    """Parse a conda match spec into name and version constraint.

    Format: 'name version_constraint [build_string]'
    We keep the full version_constraint including build string
    as that's what the native tools report.
    """
    parts = spec.strip().split(None, 1)
    name = parts[0]
    version_constraint = parts[1] if len(parts) > 1 else None
    return name, version_constraint


def build_annatto_schema(index_json, about_json):
    """Transform raw conda metadata into Annatto source-of-truth schema."""
    if index_json is None:
        return {"error": "no info/index.json found"}

    name = index_json.get('name')
    version = index_json.get('version')

    # Description: from about.json, summary preferred, description fallback
    description = None
    if about_json:
        summary = about_json.get('summary')
        if summary and isinstance(summary, str) and summary.strip():
            description = summary
        else:
            desc = about_json.get('description')
            if desc and isinstance(desc, str) and desc.strip():
                description = desc

    # License from index.json
    license_val = index_json.get('license')
    if license_val is not None and (not isinstance(license_val, str) or not license_val.strip()):
        license_val = None

    # Publisher: always null for conda
    publisher = None

    # Timestamp: millis since epoch -> ISO 8601
    published_at = None
    timestamp = index_json.get('timestamp')
    if timestamp is not None:
        try:
            ts_millis = int(timestamp)
            dt = datetime.fromtimestamp(ts_millis / 1000.0, tz=timezone.utc)
            published_at = dt.strftime('%Y-%m-%dT%H:%M:%S') + '+00:00'
            # Match Java's ISO_OFFSET_DATE_TIME format exactly
            # Python gives us microseconds, Java doesn't by default at whole seconds
        except (ValueError, OSError, OverflowError):
            published_at = None

    # Dependencies from depends array (match specs)
    dependencies = []
    depends = index_json.get('depends', [])
    if depends:
        for spec in depends:
            if not spec:
                continue
            dep_name, version_constraint = parse_match_spec(spec)
            dependencies.append({
                'name': dep_name,
                'versionConstraint': version_constraint,
                'scope': 'runtime'
            })

    # Build and subdir (extra fields for PURL qualifiers)
    build = index_json.get('build')
    subdir = index_json.get('subdir')

    return {
        'name': name,
        'simpleName': name,
        'version': version,
        'description': description,
        'license': license_val,
        'publisher': publisher,
        'publishedAt': published_at,
        'dependencies': dependencies,
        'build': build,
        'subdir': subdir,
    }


def extract(path):
    """Extract metadata from a conda package file."""
    if path.endswith('.conda'):
        index_json, about_json = extract_from_conda(path)
    elif path.endswith('.tar.bz2'):
        index_json, about_json = extract_from_tar_bz2(path)
    else:
        return {"error": f"unknown format: {path}"}
    return build_annatto_schema(index_json, about_json)


def main():
    if len(sys.argv) < 2:
        print("Usage: extract.py [--batch] <package-file> [...]", file=sys.stderr)
        sys.exit(1)

    batch_mode = '--batch' in sys.argv
    files = [a for a in sys.argv[1:] if a != '--batch']

    for path in files:
        result = extract(path)
        if batch_mode:
            basename = os.path.basename(path)
            # Strip extension to get base name
            if basename.endswith('.tar.bz2'):
                base = basename[:-len('.tar.bz2')]
            elif basename.endswith('.conda'):
                base = basename[:-len('.conda')]
            else:
                base = os.path.splitext(basename)[0]
            out_path = f"/work/out/{base}-expected.json"
            with open(out_path, 'w') as f:
                json.dump(result, f, indent=2)
                f.write('\n')
            print(f"Wrote: {out_path}")
        else:
            print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
