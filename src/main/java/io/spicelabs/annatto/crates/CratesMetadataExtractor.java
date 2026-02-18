/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.crates;

import io.spicelabs.annatto.common.MetadataResult;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/**
 * Extracts metadata from Crates.io artifact contents.
 */
public final class CratesMetadataExtractor {

    private CratesMetadataExtractor() {
        // utility class
    }

    /**
     * Extracts metadata from the given Crates.io artifact stream.
     *
     * @param inputStream the artifact input stream
     * @return the extracted metadata result
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream) {
        throw new UnsupportedOperationException("CratesMetadataExtractor.extract is not yet implemented");
    }
}
