#!/usr/bin/env python3
"""Extracts metadata from Conda .tar.bz2 or .conda packages."""
import json, sys, tarfile, zipfile, os

def extract(path):
    if path.endswith('.conda'):
        with zipfile.ZipFile(path) as zf:
            for name in zf.namelist():
                if name == 'metadata.json' or name.endswith('/index.json'):
                    return json.loads(zf.read(name))
            # Try info-*.tar.zst inside the .conda
            return {"error": "no metadata found in .conda"}
    else:
        with tarfile.open(path, 'r:bz2') as tf:
            try:
                f = tf.extractfile('info/index.json')
                return json.loads(f.read())
            except KeyError:
                return {"error": "no info/index.json in .tar.bz2"}

if __name__ == '__main__':
    print(json.dumps(extract(sys.argv[1]), indent=2))
