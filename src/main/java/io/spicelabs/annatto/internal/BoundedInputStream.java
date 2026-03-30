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
 * <p>Throws SecurityException if the limit is exceeded.
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
        if (limitExceeded) {
            return -1;
        }

        // Adjust len to not exceed limit
        long remaining = maxBytes - bytesRead;
        if (remaining <= 0) {
            throw new AnnattoException.SecurityException(
                "Entry exceeds size limit: " + entryName + " (max " + maxBytes + " bytes)");
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
        // Don't skip more than remaining limit
        long remaining = maxBytes - bytesRead;
        long toSkip = Math.min(n, remaining);
        long skipped = delegate.skip(toSkip);
        bytesRead += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
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
