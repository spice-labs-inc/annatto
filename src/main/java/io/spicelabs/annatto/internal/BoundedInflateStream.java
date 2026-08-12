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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An {@link InputStream} that bounds the total number of bytes read from a decompressor.
 *
 * <p>Place it BETWEEN a decompressor (gzip/zstd/bzip2/zip deflate) and an archive reader so
 * that every byte flowing out of the decompressor - including bytes consumed by
 * tar-skip/header reads - counts against the budget. This is the load-bearing control for
 * the Phase 7 compressed/decompressed resource budgets (ADR-005).
 *
 * <p>Semantics (fail-closed):
 * <ul>
 *   <li>Reading within the budget is transparent.</li>
 *   <li>Reading MORE than {@code maxBytes} total throws
 *       {@link AnnattoException.SecurityException} and sets the {@link #exceeded()} latch -
 *       budgets must FAIL the operation, never silently truncate an archive (a truncated tar
 *       would otherwise surface as a confusing {@code Truncated TAR archive} I/O error).</li>
 *   <li>{@code skip()} is implemented as counted read-discard so it cannot bypass the budget.</li>
 *   <li>An optional callback fires when the budget is exceeded (used by per-pass entry-stream
 *       budgets to fail-fast subsequent iterations).</li>
 * </ul>
 */
public final class BoundedInflateStream extends InputStream {

    private final InputStream delegate;
    private final long maxBytes;
    private final String entryName;
    private final Runnable onExceed;
    private long count;
    private boolean exceeded;

    public BoundedInflateStream(InputStream delegate, long maxBytes, String entryName) {
        this(delegate, maxBytes, entryName, () -> {
        });
    }

    public BoundedInflateStream(InputStream delegate, long maxBytes, String entryName, Runnable onExceed) {
        this.delegate = Objects.requireNonNull(delegate);
        this.maxBytes = maxBytes;
        this.entryName = entryName;
        this.onExceed = Objects.requireNonNull(onExceed);
    }

    @Override
    public int read() throws IOException {
        if (exceeded) {
            return -1;
        }
        int b = delegate.read();
        if (b != -1) {
            count++;
            if (count > maxBytes) {
                failExceeded();
            }
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (exceeded) {
            return -1;
        }
        if (len <= 0) {
            return 0;
        }
        int r = delegate.read(b, off, len);
        if (r > 0) {
            count += r;
            if (count > maxBytes) {
                failExceeded();
            }
        }
        return r;
    }

    @Override
    public long skip(long n) throws IOException {
        if (exceeded || n <= 0) {
            return 0;
        }
        // Count skipped bytes as budget consumed (read-and-discard keeps accounting exact).
        long skipped = 0;
        byte[] scratch = new byte[8192];
        while (skipped < n) {
            int toRead = (int) Math.min(scratch.length, n - skipped);
            int r = delegate.read(scratch, 0, toRead);
            if (r < 0) {
                break;
            }
            skipped += r;
            count += r;
            if (count > maxBytes) {
                failExceeded();
            }
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        if (exceeded) {
            return 0;
        }
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /** True once the budget has been exceeded (latch). */
    public boolean exceeded() {
        return exceeded;
    }

    /** Total bytes pulled from the delegate so far. */
    public long count() {
        return count;
    }

    private void failExceeded() {
        exceeded = true;
        onExceed.run();
        throw new AnnattoException.SecurityException(
            "Decompressed data exceeds size limit: " + entryName +
            " (max " + maxBytes + " bytes)");
    }
}