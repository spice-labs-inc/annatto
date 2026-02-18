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
package io.spicelabs.annatto.rubygems;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/**
 * Extracts metadata from the {@code metadata.gz} entry inside a {@code .gem} tar archive.
 */
public final class RubygemsMetadataExtractor {

    private RubygemsMetadataExtractor() {
        // static utility class
    }

    /**
     * Extracts RubyGems metadata from the given input stream.
     *
     * @param stream the input stream of the {@code .gem} artifact
     * @return the populated {@link RubygemsMemento}
     */
    public static @NotNull RubygemsMemento extract(@NotNull InputStream stream) {
        throw new UnsupportedOperationException("Not yet implemented: RubygemsMetadataExtractor.extract");
    }
}
