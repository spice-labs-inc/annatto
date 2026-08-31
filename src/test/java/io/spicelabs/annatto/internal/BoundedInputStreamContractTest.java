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
package io.spicelabs.annatto.internal;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.testutil.CountingInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InputStream contract tests for {@link BoundedInputStream} (Fresh Scent Phase 3, A6,
 * catalog §12).
 */
class BoundedInputStreamContractTest {

    // Requirement: catalog §12 / finding A6 / plan Phase 3.5
    // Theory: len == 0 must return 0 WITHOUT touching the delegate (JDK readNBytes calls
    //         read(buf,off,0) at exact buffer fills — forwarding it to a strict delegate
    //         breaks standard consumers).
    // Revert-check: removing the len==0 guard makes the CountingInputStream see a read.
    @Test
    void lenZeroReturnsZeroWithoutDelegateRead() throws IOException {
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream("abc".getBytes()));
        BoundedInputStream stream = new BoundedInputStream(counting, 1024, "e");
        assertThat(stream.read(new byte[4], 1, 0)).isZero();
        assertThat(counting.readCount()).isZero();
    }

    // Requirement: catalog §12 / finding A6 / plan Phase 3.5
    // Theory: the read that CROSSES the limit throws SecurityException (a probe at the
    //         boundary detects the surplus byte); after the latch, read() and read(byte[])
    //         behave IDENTICALLY (both return -1 — the stream is exhausted; the loud error
    //         already fired on the crossing read).
    // Boundaries: limit / limit+1.
    // Revert-check: restoring the old divergent behavior (read(byte[]) throwing on the
    //               remaining<=0 branch, or returning 0 forever) fails the assertions.
    @Test
    void crossingReadThrowsAndPostLatchReadsReturnEof() throws IOException {
        byte[] content = new byte[10];
        BoundedInputStream stream = new BoundedInputStream(new ByteArrayInputStream(content), 9, "e");
        assertThatThrownBy(() -> {
            byte[] buf = new byte[16];
            int r;
            while ((r = stream.read(buf)) != -1) {
                // drain until the crossing read throws
            }
        }).isInstanceOf(AnnattoException.SecurityException.class);
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.read(new byte[16])).isEqualTo(-1);
    }

    // Requirement: catalog §12 / finding A6 / plan Phase 3.5
    // Theory: skip() must be charged against the limit — a skip past the cap throws.
    // Revert-check: the old delegate.skip() passthrough bypasses the cap.
    @Test
    void skipChargesAgainstLimit() {
        BoundedInputStream stream = new BoundedInputStream(new ByteArrayInputStream(new byte[10]), 5, "e");
        assertThatThrownBy(() -> stream.skip(8))
                .isInstanceOf(AnnattoException.SecurityException.class);
    }

    // Requirement: catalog §12 / plan Phase 3.5 (bounds-check on the public contract)
    @Test
    void boundsChecked() throws IOException {
        BoundedInputStream stream = new BoundedInputStream(new ByteArrayInputStream(new byte[1]), 1024, "e");
        assertThatThrownBy(() -> stream.read(new byte[4], -1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> stream.read(new byte[4], 0, 5)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    // Requirement: exact-limit reads succeed (boundary discipline, no off-by-one)
    @Test
    void readsUpToLimitSucceed() throws IOException {
        byte[] content = "0123456789".getBytes();
        BoundedInputStream stream = new BoundedInputStream(new ByteArrayInputStream(content), 10, "e");
        assertThat(stream.readAllBytes()).isEqualTo(content);
    }
}
