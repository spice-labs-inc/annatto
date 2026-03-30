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

package io.spicelabs.annatto.internal;

import io.spicelabs.annatto.AnnattoException;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates archive entry paths for security.
 *
 * <p>Rejects:
 * <ul>
 *   <li>Paths containing ".." (path traversal)</li>
 *   <li>Paths with null bytes</li>
 *   <li>Absolute paths</li>
 *   <li>Paths longer than 4096 characters</li>
 * </ul>
 */
public final class PathValidator {

    private static final int MAX_PATH_LENGTH = 4096;

    private PathValidator() {
        // Utility class
    }

    /**
     * Validate an entry name from an archive.
     *
     * @param name the entry name
     * @return normalized name if valid
     * @throws AnnattoException.SecurityException if path is invalid
     */
    public static String validateEntryName(String name) {
        if (name == null) {
            throw new AnnattoException.SecurityException("Entry name is null");
        }

        // Check length
        if (name.length() > MAX_PATH_LENGTH) {
            throw new AnnattoException.SecurityException(
                "Entry path exceeds maximum length: " + name.length() + " > " + MAX_PATH_LENGTH);
        }

        // Check for null bytes (injection attack)
        if (name.indexOf('\0') >= 0) {
            throw new AnnattoException.SecurityException("Entry name contains null byte");
        }

        // Normalize and check for traversal
        Path path = Paths.get(name).normalize();

        // Check if absolute
        if (path.isAbsolute()) {
            throw new AnnattoException.SecurityException("Entry name is absolute path: " + sanitize(name));
        }

        // Check for traversal after normalization
        String normalized = path.toString();
        if (normalized.startsWith("..")) {
            throw new AnnattoException.SecurityException("Entry name contains path traversal: " + sanitize(name));
        }

        // Check for embedded .. in path
        for (int i = 0; i < normalized.length() - 2; i++) {
            if (normalized.charAt(i) == '.' &&
                normalized.charAt(i + 1) == '.' &&
                (i + 2 == normalized.length() || normalized.charAt(i + 2) == '/')) {
                throw new AnnattoException.SecurityException("Entry name contains path traversal: " + sanitize(name));
            }
        }

        return normalized;
    }

    /**
     * Validate a symlink target.
     *
     * @param target the symlink target
     * @param baseDir the base directory (archive root)
     * @return true if target stays within archive
     */
    public static boolean isSymlinkSafe(String target, Path baseDir) {
        if (target == null || target.isEmpty()) {
            return false;
        }

        // Check for null bytes
        if (target.indexOf('\0') >= 0) {
            return false;
        }

        // Resolve target against base and normalize
        Path targetPath = baseDir.resolve(target).normalize();

        // Check if still within base directory
        return targetPath.startsWith(baseDir);
    }

    /**
     * Sanitize a path for error messages (don't leak internal details).
     */
    private static String sanitize(String path) {
        if (path == null) {
            return "null";
        }
        // Only show first 100 chars, remove any internal paths
        String truncated = path.length() > 100 ? path.substring(0, 100) + "..." : path;
        // Replace backslashes with forward slashes for consistency
        return truncated.replace('\\', '/');
    }
}
