#!/usr/bin/env python3
"""Finds exact conda package filenames from repodata for our test corpus."""
import json
import subprocess
import sys


def fetch_repodata(subdir):
    """Fetch repodata.json for a subdir from conda-forge."""
    url = f"https://conda.anaconda.org/conda-forge/{subdir}/repodata.json"
    result = subprocess.run(
        ["curl", "-fsSL", url],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"Failed to fetch repodata for {subdir}", file=sys.stderr)
        return None
    return json.loads(result.stdout)


def find_one_package(packages, name, version):
    """Find the first matching package entry."""
    for filename, info in packages.items():
        if info.get("name") == name and info.get("version") == version:
            return filename
    return None


def main():
    # Fetch repodata for linux-64 and noarch
    print("Fetching linux-64 repodata...", file=sys.stderr)
    linux64 = fetch_repodata("linux-64")
    print("Fetching noarch repodata...", file=sys.stderr)
    noarch = fetch_repodata("noarch")

    if not linux64 or not noarch:
        sys.exit(1)

    linux64_conda = linux64.get("packages.conda", {})
    linux64_tar = linux64.get("packages", {})
    noarch_conda = noarch.get("packages.conda", {})
    noarch_tar = noarch.get("packages", {})

    # Modern .conda format packages (25)
    conda_linux64 = [
        ("numpy", "1.26.4"),
        ("pandas", "2.2.0"),
        ("scipy", "1.12.0"),
        ("cryptography", "42.0.0"),
        ("pyyaml", "6.0.1"),
        ("pillow", "10.2.0"),
        ("scikit-learn", "1.4.0"),
        ("matplotlib-base", "3.8.2"),
        ("zlib", "1.3.1"),
        ("openssl", "3.2.1"),
        ("libgcc-ng", "13.2.0"),
        ("ca-certificates", "2024.2.2"),
        ("sqlite", "3.45.0"),
        ("ncurses", "6.4"),
        ("curl", "8.5.0"),
        ("bzip2", "1.0.8"),
        ("xz", "5.2.6"),
    ]

    conda_noarch = [
        ("requests", "2.31.0"),
        ("flask", "3.0.0"),
        ("boto3", "1.34.0"),
        ("certifi", "2024.2.2"),
        ("setuptools", "69.0.3"),
        ("six", "1.16.0"),
        ("jinja2", "3.1.3"),
        ("click", "8.1.7"),
    ]

    # Legacy .tar.bz2 format packages (25)
    tar_linux64 = [
        ("numpy", "1.21.0"),
        ("pandas", "1.3.0"),
        ("python", "3.9.7"),
        ("setuptools", "58.0.4"),
        ("certifi", "2021.5.30"),
        ("pyyaml", "5.4.1"),
        ("zlib", "1.2.11"),
        ("openssl", "1.1.1l"),
        ("libgcc-ng", "11.2.0"),
        ("ca-certificates", "2021.5.30"),
        ("readline", "8.1"),
        ("sqlite", "3.36.0"),
        ("bzip2", "1.0.8"),
        ("xz", "5.2.5"),
        ("ncurses", "6.2"),
        ("libffi", "3.3"),
        ("chardet", "4.0.0"),
        ("click", "8.0.1"),
    ]

    tar_noarch = [
        ("pip", "21.2.4"),
        ("wheel", "0.37.0"),
        ("six", "1.16.0"),
        ("idna", "3.2"),
        ("urllib3", "1.26.6"),
        ("requests", "2.26.0"),
        ("jinja2", "3.0.1"),
    ]

    found = []
    missing = []

    # Find .conda packages
    for name, version in conda_linux64:
        fn = find_one_package(linux64_conda, name, version)
        if fn:
            found.append(("linux-64", fn))
        else:
            missing.append(("conda", "linux-64", name, version))

    for name, version in conda_noarch:
        fn = find_one_package(noarch_conda, name, version)
        if fn:
            found.append(("noarch", fn))
        else:
            missing.append(("conda", "noarch", name, version))

    # Find .tar.bz2 packages
    for name, version in tar_linux64:
        fn = find_one_package(linux64_tar, name, version)
        if fn:
            found.append(("linux-64", fn))
        else:
            missing.append(("tar.bz2", "linux-64", name, version))

    for name, version in tar_noarch:
        fn = find_one_package(noarch_tar, name, version)
        if fn:
            found.append(("noarch", fn))
        else:
            missing.append(("tar.bz2", "noarch", name, version))

    # Output found packages as download specs
    for subdir, filename in found:
        print(f"{subdir}/{filename}")

    if missing:
        print("\n--- MISSING ---", file=sys.stderr)
        for item in missing:
            print(f"  {item}", file=sys.stderr)

    print(f"\nFound: {len(found)}, Missing: {len(missing)}", file=sys.stderr)


if __name__ == "__main__":
    main()
