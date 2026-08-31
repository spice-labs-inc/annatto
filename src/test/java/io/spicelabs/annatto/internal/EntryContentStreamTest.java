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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit contract tests for {@link EntryContentStream} (Fresh Scent Phase 2).
 *
 * <p>Each test documents the catalog section that motivated it, the theory, boundaries,
 * and which change a mental revert would break.
 */
class EntryContentStreamTest {

    // Requirement: catalog §2 (whole-file materialization) / finding A2 / plan Phase 2.1
    // Theory: the wrapper must be LAZY — zero bytes flow from the delegate until the
    //         consumer's first read (this pins the fix against the §13 anti-pattern
    //         "equivalence tests that don't test the property").
    // Revert-check: replacing the wrapper with the old eager BAOS copy loop fails this.
    @Test
    void zeroBytesReadBeforeFirstConsumerRead() throws IOException {
        byte[] content = "hello world".getBytes();
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(content));
        EntryContentStream stream = EntryContentStream.bounded(counting, "e.txt",
                content.length, 1024);
        assertThat(counting.readCount()).isZero();
        assertThat(stream.read()).isEqualTo('h');
        assertThat(counting.readCount()).isEqualTo(1);
    }

    // Requirement: catalog §3 / finding A2 / plan Phase 2.1
    // Theory: the per-entry cap must trip exactly at cap+1, not cap (boundary discipline).
    // Boundaries: cap−1 / cap / cap+1.
    // Revert-check: removing the account() cap check makes cap+1 succeed.
    @Test
    void entryCapBoundaries() {
        byte[] content = new byte[10];
        EntryContentStream ok = EntryContentStream.bounded(new ByteArrayInputStream(content),
                "e", 10, 10);
        assertThat(ok).isNotNull();

        EntryContentStream over = EntryContentStream.bounded(new ByteArrayInputStream(content),
                "e", 10, 9);
        assertThatThrownBy(() -> {
            byte[] buf = new byte[16];
            int r;
            while ((r = over.read(buf)) != -1) {
                // crossing cap must throw
            }
        }).isInstanceOf(AnnattoException.SecurityException.class);
    }

    // Requirement: catalog §3 / plan Phase 2.1 (case b/c chains)
    // Theory: the optional per-pass budget must be charged on READ (not at openStream) and
    //         fire the latch callback exactly once when crossed.
    // Boundaries: pass cap−1 / cap / cap+1.
    // Revert-check: removing the pass-charging branch lets the latch stay unset.
    @Test
    void passBudgetChargedOnReadAndLatches() throws IOException {
        byte[] content = new byte[10];
        AtomicLong charged = new AtomicLong();
        AtomicBoolean latched = new AtomicBoolean();
        EntryContentStream stream = EntryContentStream.withPassBudget(
                new ByteArrayInputStream(content), "e", 10, 1024, charged, 9,
                () -> latched.set(true));

        // Lazy: opening does not charge.
        assertThat(charged.get()).isZero();
        assertThatThrownBy(() -> stream.readAllBytes())
                .isInstanceOf(AnnattoException.SecurityException.class);
        assertThat(charged.get()).isEqualTo(10);
        assertThat(latched.get()).isTrue();
    }

    // Requirement: catalog §12 / finding A6 / plan Phase 2.2
    // Theory: len == 0 must return 0 without touching the delegate (JDK readNBytes contract).
    // Revert-check: forwarding len=0 to a strict delegate breaks standard consumers.
    @Test
    void lenZeroReturnsZero() throws IOException {
        EntryContentStream stream = EntryContentStream.bounded(
                new ByteArrayInputStream("abc".getBytes()), "e", 3, 1024);
        assertThat(stream.read(new byte[4], 1, 0)).isZero();
    }

    // Requirement: plan Phase 2.2
    // Theory: close() is a no-op on the DELEGATE — the archive must stay usable for the
    //         next entry (old BoundedInputStream.close() closed the delegate).
    // Revert-check: making close() close the delegate breaks the next read.
    @Test
    void closeDoesNotCloseDelegate() throws IOException {
        byte[] content = "abc".getBytes();
        ByteArrayInputStream delegate = new ByteArrayInputStream(content);
        EntryContentStream stream = EntryContentStream.bounded(delegate, "e", 3, 1024);
        stream.close();
        assertThat(delegate.read()).isEqualTo('a');
    }

    // Requirement: catalog §6 / finding A2/RT#6 / plan Phase 2.3
    // Theory: a regular-file entry whose delegate ends before declaredSize is TRUNCATED —
    //         must throw IOException, never return silently partial data.
    // Boundaries: exact-size (no throw) vs short (throw).
    // Revert-check: removing checkTruncation returns the partial bytes silently.
    @Test
    void truncatedEntryThrows() throws IOException {
        EntryContentStream shortStream = EntryContentStream.bounded(
                new ByteArrayInputStream("abc".getBytes()), "e", 10, 1024);
        assertThatThrownBy(shortStream::readAllBytes).isInstanceOf(IOException.class);

        EntryContentStream exact = EntryContentStream.bounded(
                new ByteArrayInputStream("abc".getBytes()), "e", 3, 1024);
        assertThat(exact.readAllBytes()).isEqualTo("abc".getBytes());
    }

    // Requirement: catalog §5 / plan Phase 2
    // Theory: skip() is counted read-and-discard — it charges caps and detects truncation,
    //         and a no-progress delegate must not spin.
    // Revert-check: a raw delegate.skip() would bypass the entry cap.
    @Test
    void skipCountsAgainstCaps() {
        EntryContentStream stream = EntryContentStream.bounded(
                new ByteArrayInputStream(new byte[10]), "e", 10, 5);
        assertThatThrownBy(() -> stream.skip(8))
                .isInstanceOf(AnnattoException.SecurityException.class);
    }

    // Requirement: catalog §5
    // Theory: a delegate that returns 0 repeatedly (no progress) must terminate with an
    //         error, not spin (bounded by the read loop in skip()).
    // Revert-check: removing the read==0 check makes skip spin on a broken delegate.
    @Test
    void noProgressDelegateDoesNotSpin() {
        InputStream zeroReader = new InputStream() {
            @Override
            public int read() throws IOException {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return 0;
            }
        };
        EntryContentStream stream = EntryContentStream.bounded(zeroReader, "e", 10, 1024);
        assertThatThrownBy(() -> stream.skip(5)).isInstanceOf(IOException.class);
    }
}
