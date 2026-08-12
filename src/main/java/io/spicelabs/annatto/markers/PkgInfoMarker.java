/* Copyright 2026 Spice Labs, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.annatto.markers;

/**
 * Single source of truth for PyPI sdist markers (Phase 7): a top-level
 * {@code <dist-dir>/PKG-INFO} (extraction) or {@code <dist-dir>/pyproject.toml} (routing),
 * each exactly two segments deep.
 */
public final class PkgInfoMarker {

    private PkgInfoMarker() {
    }

    /** Routing marker for PyPI sdist content (PKG-INFO or pyproject.toml at top level). */
    public static boolean isSdistMarker(String entryName) {
        return is(name -> "PKG-INFO".equals(name) || "pyproject.toml".equals(name), entryName);
    }

    /** Extraction marker: the authoritative PKG-INFO metadata file. */
    public static boolean isPkgInfo(String entryName) {
        return is(name -> "PKG-INFO".equals(name), entryName);
    }

    private static boolean is(java.util.function.Predicate<String> leaf, String entryName) {
        String n = Markers.normalize(entryName);
        if (!Markers.noTraversal(n)) {
            return false;
        }
        String[] parts = Markers.segments(n);
        return parts.length == 2 && leaf.test(parts[1]);
    }

    /** A wheel's {@code <name>.dist-info/METADATA} marker (used by the wheel metadata pass). */
    public static boolean isDistInfoMetadata(String entryName) {
        String n = Markers.normalize(entryName);
        if (!Markers.noTraversal(n)) {
            return false;
        }
        String[] parts = Markers.segments(n);
        return parts.length == 2 && parts[0].endsWith(".dist-info") && "METADATA".equals(parts[1]);
    }
}