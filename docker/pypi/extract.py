#!/usr/bin/env python3
"""Extracts metadata from PyPI sdist (.tar.gz) or wheel (.whl) packages.

Outputs Annatto source-of-truth JSON schema:
{name, simpleName, version, description, license, publisher, dependencies: [{name, versionConstraint, scope}]}

Uses only Python stdlib (email.parser, zipfile, tarfile) - no pkginfo dependency.
"""
import json
import re
import sys
import tarfile
import zipfile
from email.parser import FeedParser


def parse_rfc822(text):
    """Parse RFC 822 metadata text into a dict of header -> list of values."""
    parser = FeedParser()
    parser.feed(text)
    msg = parser.close()
    headers = {}
    for key in msg.keys():
        normalized = key.strip()
        if normalized not in headers:
            headers[normalized] = []
        headers[normalized].append(msg.get(key, '').strip())
    # Handle repeated headers properly (e.g. Requires-Dist, Classifier)
    headers = {}
    for key, value in msg.items():
        normalized = key.strip()
        if normalized not in headers:
            headers[normalized] = []
        headers[normalized].append(value.strip() if value else '')
    # Store body if present
    body = msg.get_payload()
    if body and body.strip():
        headers['__body__'] = [body.strip()]
    return headers


def extract_metadata_from_wheel(path):
    """Extract METADATA text from a .whl (zip) file."""
    with zipfile.ZipFile(path, 'r') as zf:
        for name in zf.namelist():
            # Match *.dist-info/METADATA at exactly one level deep
            parts = name.split('/')
            if len(parts) == 2 and parts[0].endswith('.dist-info') and parts[1] == 'METADATA':
                return zf.read(name).decode('utf-8')
    raise ValueError(f"No .dist-info/METADATA found in {path}")


def extract_metadata_from_sdist(path):
    """Extract PKG-INFO text from a .tar.gz file."""
    with tarfile.open(path, 'r:gz') as tf:
        for member in tf.getmembers():
            parts = member.name.split('/')
            if len(parts) == 2 and parts[1] == 'PKG-INFO':
                f = tf.extractfile(member)
                if f:
                    return f.read().decode('utf-8')
    raise ValueError(f"No PKG-INFO found in {path}")


def get_header(headers, key, skip_unknown=True):
    """Get first non-UNKNOWN value for a header key."""
    values = headers.get(key, [])
    for v in values:
        if skip_unknown and v.upper() == 'UNKNOWN':
            continue
        if v:
            return v
    return None


def extract_license(headers):
    """Extract license with priority: License-Expression > License > Classifier."""
    # 1. License-Expression (SPDX)
    le = get_header(headers, 'License-Expression', skip_unknown=True)
    if le:
        return le

    # 2. License header (skip UNKNOWN)
    lic = get_header(headers, 'License', skip_unknown=True)
    if lic:
        # Some packages put multi-line license text here; use first line if short
        lines = lic.strip().split('\n')
        if len(lines) == 1 or len(lic) < 200:
            return lic.strip()
        # If very long, it's probably the full license text - skip and use classifiers
        # Actually return it as-is since that's what the metadata says
        return lic.strip()

    # 3. Classifier: License :: OSI Approved :: ...
    classifiers = headers.get('Classifier', [])
    license_classifiers = []
    for c in classifiers:
        if c.startswith('License :: OSI Approved :: '):
            # Extract the license name after the last ::
            parts = c.split(' :: ')
            if len(parts) >= 3:
                license_classifiers.append(parts[-1])
        elif c.startswith('License :: ') and 'OSI Approved' not in c:
            parts = c.split(' :: ')
            if len(parts) >= 2:
                license_classifiers.append(parts[-1])
    if license_classifiers:
        return ' OR '.join(license_classifiers)

    return None


def extract_name_from_email_field(email_field):
    """Extract name from 'Name <email>' format."""
    if not email_field:
        return None
    # Try "Name <email>" format
    match = re.match(r'^([^<]+)<', email_field)
    if match:
        name = match.group(1).strip()
        # Strip surrounding quotes if present
        if name.startswith('"') and name.endswith('"'):
            name = name[1:-1].strip()
        if name:
            return name
    # If no angle bracket, might just be an email
    # Try to extract name from "name@domain" -> None
    if '@' in email_field and '<' not in email_field:
        return None
    return email_field.strip() if email_field.strip() else None


def extract_publisher(headers):
    """Extract publisher with priority: Author > Author-email name > Maintainer > Maintainer-email."""
    # 1. Author (skip UNKNOWN)
    author = get_header(headers, 'Author', skip_unknown=True)
    if author:
        return author

    # 2. Author-email -> extract name part
    author_email = get_header(headers, 'Author-email', skip_unknown=True)
    if author_email:
        name = extract_name_from_email_field(author_email)
        if name:
            return name

    # 3. Maintainer
    maintainer = get_header(headers, 'Maintainer', skip_unknown=True)
    if maintainer:
        return maintainer

    # 4. Maintainer-email
    maintainer_email = get_header(headers, 'Maintainer-email', skip_unknown=True)
    if maintainer_email:
        name = extract_name_from_email_field(maintainer_email)
        if name:
            return name

    return None


def parse_requires_dist(line):
    """Parse a Requires-Dist line into (name, versionConstraint).

    PEP 508 format: name [extras] (version-spec) ; env-markers
    """
    line = line.strip()
    if not line:
        return None

    # Strip environment markers (after ;)
    semicolon = line.find(';')
    if semicolon >= 0:
        line = line[:semicolon].strip()

    # Strip extras (inside [])
    bracket = line.find('[')
    if bracket >= 0:
        close = line.find(']', bracket)
        if close >= 0:
            line = line[:bracket] + line[close + 1:]
        line = line.strip()

    # Split name and version constraint
    # Name is everything up to first space, (, or version operator
    match = re.match(r'^([A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?)', line)
    if not match:
        return None

    name = match.group(1)
    rest = line[match.end():].strip()

    # Extract version constraint - strip parentheses if present
    if rest.startswith('(') and rest.endswith(')'):
        rest = rest[1:-1].strip()

    version_constraint = rest if rest else None

    return {
        'name': name,
        'versionConstraint': version_constraint,
        'scope': 'runtime'
    }


def extract(path):
    """Extract metadata from a PyPI package file."""
    if path.endswith('.whl'):
        text = extract_metadata_from_wheel(path)
    elif path.endswith('.tar.gz'):
        text = extract_metadata_from_sdist(path)
    else:
        raise ValueError(f"Unknown format: {path}")

    headers = parse_rfc822(text)

    name = get_header(headers, 'Name', skip_unknown=True)
    version = get_header(headers, 'Version', skip_unknown=True)
    summary = get_header(headers, 'Summary', skip_unknown=True)
    description = summary  # Use Summary header, not the body

    license_val = extract_license(headers)
    publisher = extract_publisher(headers)

    # Parse dependencies
    dependencies = []
    requires_dist_list = headers.get('Requires-Dist', [])
    for rd in requires_dist_list:
        dep = parse_requires_dist(rd)
        if dep:
            dependencies.append(dep)

    return {
        'name': name,
        'simpleName': name,  # PyPI has no namespace concept
        'version': version,
        'description': description,
        'license': license_val,
        'publisher': publisher,
        'dependencies': dependencies
    }


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: extract.py <package-file>", file=sys.stderr)
        sys.exit(1)
    result = extract(sys.argv[1])
    print(json.dumps(result, indent=2))
