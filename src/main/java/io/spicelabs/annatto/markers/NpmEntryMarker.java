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
 * Single source of truth for the npm {@code package.json} marker (Phase 7).
 *
 * <p>Used by the ROUTER (content disambiguation), the npm PACKAGE metadata scan, and the npm
 * METADATA EXTRACTOR so the three can never disagree. Semantics (matching the documented
 * extractor rule, preserved by {@code NpmMetadataExtractorTest.isPackageJson_*}):
 * {@code package.json} at the root, or {@code <dir>/package.json} where {@code <dir>} is a
 * single path segment. Traversal segments (`.`/`..`) are never markers.
 */
public final class NpmEntryMarker {

    private NpmEntryMarker() {
    }

    public static boolean isPackageJson(String entryName) {
        String n = Markers.normalize(entryName);
        if (!Markers.noTraversal(n)) {
            return false;
        }
        if (n.equals("package.json")) {
            return true;
        }
        if (n.endsWith("/package.json")) {
            String dir = n.substring(0, n.length() - "/package.json".length());
            return !dir.contains("/");
        }
        return false;
    }
}