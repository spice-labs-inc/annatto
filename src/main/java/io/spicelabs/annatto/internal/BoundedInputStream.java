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

/**
 * An InputStream that enforces a maximum number of bytes that can be read.
 *
 * <p>Throws SecurityException when the limit is exceeded. Contract (Fresh Scent Phase 3,
 * finding A6, catalog §12):
 * <ul>
 *   <li>{@code read(byte[], off, 0)} returns {@code 0} without touching the delegate
 *       (JDK {@code readNBytes} calls this at exact buffer fills);</li>
 *   <li>the read that CROSSES the limit throws {@code SecurityException}; after the latch
 *       both {@code read()} and {@code read(byte[],...)} behave identically and return
 *       {@code -1} (the stream is exhausted — the loud error already fired);</li>
 *   <li>{@code skip()} is charged against the limit and throws on the crossing skip.</li>
 * </ul>
 */
public class BoundedInputStream extends InputStream {

    private final InputStream delegate;
    private final long maxBytes;
    private final String entryName;
    private long bytesRead;
    private boolean limitExceeded;

    /**
     * Create a bounded input stream.
     *
     * @param delegate the underlying stream
     * @param maxBytes maximum bytes allowed
     * @param entryName entry name for error messages
     */
    public BoundedInputStream(InputStream delegate, long maxBytes, String entryName) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
        this.entryName = entryName;
        this.bytesRead = 0;
        this.limitExceeded = false;
    }

    @Override
    public int read() throws IOException {
        if (limitExceeded) {
            return -1;
        }

        int b = delegate.read();
        if (b != -1) {
            bytesRead++;
            checkLimit();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (limitExceeded) {
            return -1;
        }

        // Adjust len to not exceed limit
        long remaining = maxBytes - bytesRead;
        if (remaining <= 0) {
            // At the boundary: probe one byte. If the delegate still has data, the next
            // byte CROSSES the limit and must throw (loud); if it is at EOF, the stream
            // ends cleanly exactly at the limit. (Returning 0 here would violate the
            // InputStream contract and spin standard consumers.)
            int probe = delegate.read();
            if (probe == -1) {
                return -1;
            }
            bytesRead++;
            checkLimit(); // throws SecurityException
            return probe;
        }
        int toRead = (int) Math.min(len, remaining);
        int read = delegate.read(b, off, toRead);

        if (read > 0) {
            bytesRead += read;
            checkLimit();
        }

        return read;
    }

    private void checkLimit() {
        if (bytesRead > maxBytes) {
            limitExceeded = true;
            throw new AnnattoException.SecurityException(
                "Entry exceeds size limit: " + entryName + " (" + bytesRead + " > " + maxBytes + " bytes)");
        }
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0 || limitExceeded) {
            return 0;
        }
        // Read-and-discard keeps the limit accounting exact (skips must not bypass the cap).
        long skipped = 0;
        byte[] scratch = new byte[8192];
        while (skipped < n) {
            int toRead = (int) Math.min(scratch.length, n - skipped);
            int r = delegate.read(scratch, 0, toRead);
            if (r < 0) {
                break;
            }
            if (r == 0) {
                throw new IOException("No progress skipping bounded content: " + entryName);
            }
            bytesRead += r;
            checkLimit();
            skipped += r;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        if (limitExceeded) {
            return 0;
        }
        int avail = delegate.available();
        long remaining = maxBytes - bytesRead;
        return (int) Math.min(avail, remaining);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /**
     * Get the number of bytes read so far.
     */
    public long getBytesRead() {
        return bytesRead;
    }
}
