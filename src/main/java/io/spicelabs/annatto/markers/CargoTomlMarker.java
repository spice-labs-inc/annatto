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
 * Single source of truth for Crates.io markers (Phase 7): a top-level
 * {@code <crate-dir>/Cargo.toml} (exactly two segments).
 */
public final class CargoTomlMarker {

    private CargoTomlMarker() {
    }

    public static boolean isCargoToml(String entryName) {
        String n = Markers.normalize(entryName);
        if (!Markers.noTraversal(n)) {
            return false;
        }
        String[] parts = Markers.segments(n);
        return parts.length == 2 && "Cargo.toml".equals(parts[1]);
    }
}