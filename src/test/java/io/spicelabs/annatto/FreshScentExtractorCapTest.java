/* Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto;

import io.spicelabs.annatto.go.GoMetadataExtractor;
import io.spicelabs.annatto.npm.NpmMetadataExtractor;
import io.spicelabs.annatto.pypi.PypiMetadataExtractor;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metadata-extractor size-cap tests (Fresh Scent Phase 3, finding A5, catalog §11).
 *
 * <p>The extractor path (public {@code *MetadataExtractor}) previously disagreed with the
 * bounded {@code ecosystem/*Package} path: several {@code readStreamToString} helpers had
 * NO cap, so a hostile 20 MB metadata member was fully materialized. These tests pin the
 * 10 MB cap end-to-end through each public extractor.
 */
class FreshScentExtractorCapTest {

    private static final int CAP = 10 * 1024 * 1024;

    private static byte[] overCap() {
        return ("x".repeat(CAP + 1024)).getBytes();
    }

    // Requirement: finding A5 / plan Phase 3.3
    // Revert-check: removing the cap in PypiMetadataExtractor.readStreamToString lets the
    //               oversized METADATA parse (or OOM at scale) — the test fails.
    @Test
    void pypiWheelMetadataOverCapRejected() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo-1.0.dist-info/METADATA", overCap());
        byte[] wheel = ArchiveBuilder.zip(entries);
        assertThatThrownBy(() -> PypiMetadataExtractor.extract(
                new ByteArrayInputStream(wheel), "demo-1.0.whl"))
                .isInstanceOf(AnnattoException.MetadataExtractionException.class);
    }

    // Requirement: finding A5 / plan Phase 3.3
    // Revert-check: removing the cap in NpmMetadataExtractor.parseJsonFromStream succeeds.
    @Test
    void npmPackageJsonOverCapRejected() throws IOException {
        byte[] tgz = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", overCap()));
        assertThatThrownBy(() -> NpmMetadataExtractor.extract(
                new ByteArrayInputStream(tgz), "bomb.tgz"))
                .isInstanceOf(AnnattoException.MetadataExtractionException.class);
    }

    // Requirement: finding A5 / plan Phase 3.3
    // Revert-check: removing the cap in GoMetadataExtractor.readStreamToString succeeds.
    @Test
    void goModOverCapRejected() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("example.com/mod@v1.0.0/go.mod", overCap());
        byte[] zip = ArchiveBuilder.zip(entries);
        assertThatThrownBy(() -> GoMetadataExtractor.extract(
                new ByteArrayInputStream(zip), "mod@v1.0.0.zip"))
                .isInstanceOf(AnnattoException.MetadataExtractionException.class);
    }
}
