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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sealed source of a package's archive bytes (Phase 7).
 *
 * <p>Phase 7 removes the whole-file {@code byte[]} retention. Every read - metadata
 * extraction and each {@code streamEntries()} pass - opens FRESH streams/ZipFiles from the
 * source (so no shared mutable cast state; each pass owns its own gzip/zip inflater).
 *
 * <p>Two kinds:
 * <ul>
 *   <li>{@link PathSource}: the caller's own file ({@link io.spicelabs.annatto.LanguagePackage
 *       fromPath}); NOT owned, never deleted.</li>
 *   <li>{@link SpooledSource}: a bounded spooled temp file ({@code fromStream} and the
 *       reader's stream handoff); OWNED - the package's {@code close()} deletes it (S-5).</li>
 * </ul>
 */
public sealed interface PackageSource {

    /** The backing path. */
    Path path();

    /** Open a fresh buffered input stream over the source. */
    default InputStream openInputStream() throws IOException {
        return new BufferedInputStream(Files.newInputStream(path()), 8192);
    }

    /** True if this package owns the underlying spooled file and must delete it on close (S-5). */
    boolean ownsSpool();

    /** Release owned resources (delete spooled file). Idempotent; no-op for {@link PathSource}. */
    void releaseResources();

    /** A caller-owned file: never deleted (fromPath / read(Path,...)). */
    record PathSource(Path path) implements PackageSource {
        @Override
        public boolean ownsSpool() {
            return false;
        }

        @Override
        public void releaseResources() {
            // nothing owned
        }
    }

    /** A bounded spooled temp file: deleted on close/failure (fromStream + reader handoff). */
    record SpooledSource(Path path, Spool.Cleanup cleanup) implements PackageSource {
        @Override
        public boolean ownsSpool() {
            return true;
        }

        @Override
        public void releaseResources() {
            cleanup.run();
        }
    }
}