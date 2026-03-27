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
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Stream of package entries with automatic resource management.
 *
 * <p>Lifecycle (ADR-001):
 * <ul>
 *   <li>MUST be closed using try-with-resources</li>
 *   <li>Closing is idempotent - safe to call multiple times</li>
 *   <li>Partial consumption is OK - closes properly even if not fully consumed</li>
 *   <li>NOT thread-safe - use from single thread only</li>
 * </ul>
 *
 * <p>Test: StreamingResourceManagementTest validates all lifecycle scenarios</p>
 *
 * <p>Example usage:
 * <pre>
 * try (PackageEntryStream entries = pkg.streamEntries()) {
 *     entries.forEach(entry -&gt; {
 *         System.out.println(entry.name());
 *     });
 * }
 * </pre>
 */
@io.spicelabs.annatto.NotThreadSafe
public interface PackageEntryStream extends AutoCloseable {

    /**
     * Check if there are more entries.
     *
     * @return true if another entry is available
     * @throws IOException if archive is corrupt
     */
    boolean hasNext() throws IOException;

    /**
     * Get the next entry metadata.
     * Must call {@link #openStream()} to get content.
     *
     * @return next entry
     * @throws IOException if archive is corrupt
     * @throws java.util.NoSuchElementException if no more entries
     */
    @NotNull
    PackageEntry nextEntry() throws IOException;

    /**
     * Open an InputStream for the current entry's content.
     * The stream must be closed before calling nextEntry().
     *
     * <p>Security (ADR-005): Stream is bounded to entry's declared size.
     * Reading beyond size throws SecurityException.
     *
     * @return input stream for current entry content
     * @throws IOException if content cannot be read
     * @throws IllegalStateException if no entry is current
     */
    @NotNull
    InputStream openStream() throws IOException;

    /**
     * Iterate over all remaining entries.
     *
     * <p>Note: This method closes the entry content stream automatically
     * between entries. If you need to read content, use explicit iteration.
     *
     * @return stream of entries
     */
    default @NotNull Stream<PackageEntry> stream() {
        return StreamSupport.stream(
            Spliterators.<PackageEntry>spliteratorUnknownSize(new Iterator<PackageEntry>() {
                @Override
                public boolean hasNext() {
                    try {
                        return PackageEntryStream.this.hasNext();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public PackageEntry next() {
                    try {
                        return PackageEntryStream.this.nextEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, Spliterator.NONNULL),
            false
        ).onClose(() -> {
            try {
                PackageEntryStream.this.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Close this stream and release resources.
     * Idempotent - safe to call multiple times.
     */
    @Override
    void close();
}
