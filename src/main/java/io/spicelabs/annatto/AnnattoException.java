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

/**
 * Base exception for all Annatto errors.
 */
public class AnnattoException extends Exception {

    /**
     * @param message description of the error
     */
    public AnnattoException(@NotNull String message) {
        super(message);
    }

    /**
     * @param message description of the error
     * @param cause   the underlying cause
     */
    public AnnattoException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when an ecosystem is not supported or recognized.
     */
    public static class UnsupportedEcosystemException extends AnnattoException {
        /**
         * @param message description of the unsupported ecosystem
         */
        public UnsupportedEcosystemException(@NotNull String message) {
            super(message);
        }
    }

    /**
     * Thrown when metadata extraction fails due to parsing or I/O errors.
     */
    public static class MetadataExtractionException extends AnnattoException {
        /**
         * @param message description of the extraction failure
         * @param cause   the underlying cause
         */
        public MetadataExtractionException(@NotNull String message, @NotNull Throwable cause) {
            super(message, cause);
        }

        /**
         * @param message description of the extraction failure
         */
        public MetadataExtractionException(@NotNull String message) {
            super(message);
        }
    }

    /**
     * Thrown when a package file is structurally invalid or corrupt.
     * This is a RuntimeException so it doesn't need to be declared in method signatures.
     */
    public static class MalformedPackageException extends RuntimeException {
        /**
         * @param message description of the malformation
         */
        public MalformedPackageException(@NotNull String message) {
            super(message);
        }

        /**
         * @param message description of the malformation
         * @param cause   the underlying cause
         */
        public MalformedPackageException(@NotNull String message, @NotNull Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when a package format is not recognized.
     * Test: EcosystemRouterDisambiguationTest.ambiguousGzipDefaultsToNone
     */
    public static class UnknownFormatException extends AnnattoException {
        /**
         * @param message description of the unknown format
         */
        public UnknownFormatException(@NotNull String message) {
            super(message);
        }
    }

    /**
     * Thrown when security limits are violated (ZIP bomb, path traversal, etc.).
     * Test: SecurityLimitsTest validates all security exceptions
     *
     * <p>Note: This is a RuntimeException so it doesn't need to be declared
     * in method signatures throughout the codebase.
     */
    public static class SecurityException extends RuntimeException {
        /**
         * @param message description of the security violation
         */
        public SecurityException(@NotNull String message) {
            super(message);
        }
    }
}
