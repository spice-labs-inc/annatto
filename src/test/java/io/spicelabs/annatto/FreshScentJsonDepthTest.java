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

import io.spicelabs.annatto.ecosystem.cocoapods.CocoapodsPackage;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import io.spicelabs.annatto.ecosystem.packagist.PackagistPackage;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON depth-bomb tests (Fresh Scent Phase 3, finding A9, catalog §8 analog).
 *
 * <p>GSON's recursive-descent parser has no depth limit; a hostile {@code [[[[...]]]]}
 * member fits inside the entry caps and overflows the stack with a
 * {@code StackOverflowError} (an {@code Error} — not catchable as {@code Exception}).
 * These tests pin the {@code JsonSecurity} depth guard: a loud
 * {@code MalformedPackageException} must be thrown instead.
 */
class FreshScentJsonDepthTest {

    private static String depthBomb() {
        return "[".repeat(600) + "0" + "]".repeat(600);
    }

    // Requirement: finding A9 / plan Phase 3.4 (npm package.json)
    // Revert-check: removing JsonSecurity.checkDepth lets GSON die with StackOverflowError.
    @Test
    void npmPackageJsonDepthBombRejected() throws IOException {
        byte[] tgz = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", depthBomb().getBytes()));
        assertThatThrownBy(() -> NpmPackage.fromStream(new ByteArrayInputStream(tgz), "bomb.tgz"))
                .isInstanceOf(AnnattoException.MalformedPackageException.class)
                .isNotInstanceOf(Error.class);
    }

    // Requirement: finding A9 / plan Phase 3.4 (composer.json)
    @Test
    void packagistComposerJsonDepthBombRejected() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("composer.json", depthBomb().getBytes());
        byte[] zip = ArchiveBuilder.zip(entries);
        assertThatThrownBy(() -> PackagistPackage.fromStream(new ByteArrayInputStream(zip), "bomb.zip"))
                .isInstanceOf(AnnattoException.MalformedPackageException.class)
                .isNotInstanceOf(Error.class);
    }

    // Requirement: finding A9 / plan Phase 3.4 (podspec.json single-file entry)
    @Test
    void cocoapodsPodspecDepthBombRejected() throws IOException {
        byte[] json = depthBomb().getBytes();
        assertThatThrownBy(() -> CocoapodsPackage.fromStream(
                new ByteArrayInputStream(json), "bomb.podspec.json"))
                .isInstanceOf(AnnattoException.MalformedPackageException.class)
                .isNotInstanceOf(Error.class);
    }
}
