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

package io.spicelabs.annatto.testutil;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} wrapper that counts how many bytes were read from the delegate.
 *
 * <p>Used by the Phase 7 memory-safety tests to assert that an archive parser stops
 * consuming its source once a resource bound is hit (e.g., the required assertion
 * "drained bytes &le; sbound"). This makes the RED half of a test observable: with the
 * whole-file-buffering bug the parser drains the entire source even when the bound is
 * tiny.
 */
public final class CountingInputStream extends FilterInputStream {

    private long readCount;

    public CountingInputStream(InputStream delegate) {
        super(delegate);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            readCount++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read > 0) {
            readCount += read;
        }
        return read;
    }

    /** Number of bytes actually pulled from the underlying stream so far. */
    public long readCount() {
        return readCount;
    }
}