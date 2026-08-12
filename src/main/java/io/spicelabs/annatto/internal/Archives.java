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

import io.airlift.compress.zstd.ZstdInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Convenience builders for bounded decompression chains (Phase 7).
 *
 * <p>Every chain places a {@link BoundedInflateStream} BETWEEN the decompressor and the
 * archive reader so skipped bytes (tar-skip = decompression) count against the budget, and
 * each pass opens a fresh chain (own inflater, no shared native state).
 *
 * <p>{@code displayName} is the SANITIZED, caller-basename only (ADR-005): budget-exceeded
 * messages must never leak the spool/private temp path.
 */
public final class Archives {

    private Archives() {
    }

    /** gzip -> bounded inflate -> tar. {@code onExceed} fires when the inflate budget trips. */
    public static TarArchiveInputStream gzipTar(Path file, String displayName, long inflateBudget,
                                                Runnable onExceed) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 8192);
        InputStream gz = new java.util.zip.GZIPInputStream(raw);
        BoundedInflateStream bounded =
                new BoundedInflateStream(gz, inflateBudget, displayName, onExceed);
        return new TarArchiveInputStream(bounded, StandardCharsets.UTF_8.name());
    }

    /** bzip2 -> bounded inflate -> tar. */
    public static TarArchiveInputStream bzip2Tar(Path file, String displayName, long inflateBudget,
                                                 Runnable onExceed) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 8192);
        InputStream bz = new BZip2CompressorInputStream(raw);
        BoundedInflateStream bounded = new BoundedInflateStream(bz, inflateBudget, displayName, onExceed);
        return new TarArchiveInputStream(bounded, StandardCharsets.UTF_8.name());
    }

    /** zstd (already positioned on a zstd stream) -> bounded inflate -> tar. */
    public static TarArchiveInputStream zstdTar(InputStream zstd, String displayName, long inflateBudget,
                                                Runnable onExceed) throws IOException {
        InputStream z = new ZstdInputStream(zstd);
        BoundedInflateStream bounded = new BoundedInflateStream(z, inflateBudget, displayName, onExceed);
        return new TarArchiveInputStream(bounded, StandardCharsets.UTF_8.name());
    }

    /** Random-access zip over the source path (wheels, conda v2). */
    public static ZipFile zipFile(Path file) throws IOException {
        return new ZipFile(file.toFile());
    }
}