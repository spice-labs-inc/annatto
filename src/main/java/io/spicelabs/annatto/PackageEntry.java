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

package io.spicelabs.annatto;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * An entry in a package archive.
 *
 * <p>Security (ADR-005):
 * <ul>
 *   <li>Path traversal attempts are rejected (entry skipped or exception thrown)</li>
 *   <li>Entry names are normalized before returning</li>
 *   <li>Symlinks are validated to not escape archive root</li>
 * </ul>
 *
 * @param name entry name (normalized, no path traversal)
 * @param size uncompressed size in bytes (-1 if unknown)
 * @param isDirectory true if directory entry
 * @param isSymlink true if symbolic link
 * @param symlinkTarget target path if isSymlink (validated to be within archive)
 */
public record PackageEntry(
        @NotNull String name,
        long size,
        boolean isDirectory,
        boolean isSymlink,
        @NotNull Optional<String> symlinkTarget
) {
    /**
     * Convenience constructor for regular files.
     */
    public PackageEntry(@NotNull String name, long size) {
        this(name, size, false, false, Optional.empty());
    }

    /**
     * Convenience constructor for directories.
     */
    public static PackageEntry directory(@NotNull String name) {
        return new PackageEntry(name, 0, true, false, Optional.empty());
    }

    /**
     * Convenience constructor for symlinks.
     */
    public static PackageEntry symlink(@NotNull String name, @NotNull String target) {
        return new PackageEntry(name, 0, false, true, Optional.of(target));
    }

    /**
     * Check if this entry is a regular file.
     */
    public boolean isRegularFile() {
        return !isDirectory && !isSymlink;
    }
}
