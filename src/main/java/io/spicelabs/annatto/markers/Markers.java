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

/** Shared helpers for archive-entry marker predicates. */
final class Markers {

    private Markers() {
    }

    /** Backslash-normalize and null-guard an entry name. */
    static String normalize(String entryName) {
        return entryName == null ? "" : entryName.replace('\\', '/');
    }

    /** Split an entry name into path segments (on '/'). */
    static String[] segments(String normalized) {
        return normalized.split("/", -1);
    }

    /** No empty/`.`/`..` segments. */
    static boolean clean(String[] parts) {
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** True if a candidate name has no traversal segments (empty-safe). */
    static boolean noTraversal(String n) {
        if (n.isEmpty()) {
            return false;
        }
        return clean(segments(n));
    }
}