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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LAZY bounded view of a single package entry's content (Fresh Scent Phase 2, catalog §2).
 *
 * <p>Replaces the previous {@code ByteArrayOutputStream} materialization of entry content in
 * {@code openStream()}: the underlying entry stream (tar position / zip entry stream) is
 * wrapped directly and bytes flow on demand.
 *
 * <p>Enforces, on every read:
 * <ul>
 *   <li><b>per-entry cap</b> ({@code entryCap}) — {@code SecurityException} when crossed;</li>
 *   <li><b>per-pass budget</b> (optional — {@code null} {@code passCharged} disables it; the
 *       {@code Archives.*} inflate chains already charge their own budget in
 *       {@link BoundedInflateStream}, so those paths pass {@code null} to avoid
 *       double-charging) — {@code SecurityException} + latch callback when crossed;</li>
 *   <li><b>truncation detection</b> — when the delegate ends before {@code declaredSize}
 *       (regular files only), an {@code IOException} is thrown instead of returning a
 *       silently truncated entry (catalog §6);</li>
 *   <li><b>{@code len == 0 → 0}</b> (JDK {@code readNBytes} contract, catalog §12).</li>
 * </ul>
 *
 * <p>{@link #close()} does NOT close the delegate: the owning archive (tar/zip) manages its
 * own stream and stays usable after this view is abandoned. Skipping is implemented as
 * counted read-and-discard so caps and truncation checks cannot be bypassed.
 */
public final class EntryContentStream extends InputStream {

    private final InputStream delegate;
    private final String entryName;
    private final long declaredSize;
    private final long entryCap;
    private final AtomicLong passCharged;
    private final long passCap;
    private final Runnable onPassExceed;
    private long totalRead;
    private boolean entryLimitHit;

    private EntryContentStream(InputStream delegate, String entryName, long declaredSize,
                               long entryCap, AtomicLong passCharged, long passCap,
                               Runnable onPassExceed) {
        this.delegate = Objects.requireNonNull(delegate);
        this.entryName = Objects.requireNonNull(entryName);
        this.declaredSize = declaredSize;
        this.entryCap = entryCap;
        this.passCharged = passCharged;
        this.passCap = passCap;
        this.onPassExceed = onPassExceed;
    }

    /** Bounded view WITHOUT per-pass charging (for chains that charge in {@link BoundedInflateStream}). */
    public static EntryContentStream bounded(InputStream delegate, String entryName,
                                             long declaredSize, long entryCap) {
        return new EntryContentStream(delegate, entryName, declaredSize, entryCap,
                null, 0, null);
    }

    /** Bounded view WITH per-pass charging (zip chains and raw-tar chains). */
    public static EntryContentStream withPassBudget(InputStream delegate, String entryName,
                                                    long declaredSize, long entryCap,
                                                    AtomicLong passCharged, long passCap,
                                                    Runnable onPassExceed) {
        return new EntryContentStream(delegate, entryName, declaredSize, entryCap,
                Objects.requireNonNull(passCharged), passCap, Objects.requireNonNull(onPassExceed));
    }

    @Override
    public int read() throws IOException {
        if (entryLimitHit) {
            return -1;
        }
        int b = delegate.read();
        if (b != -1) {
            account(1);
        } else {
            checkTruncation();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (entryLimitHit) {
            return -1;
        }
        int r = delegate.read(b, off, len);
        if (r > 0) {
            account(r);
        } else if (r < 0) {
            checkTruncation();
        }
        return r;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0 || entryLimitHit) {
            return 0;
        }
        // Read-and-discard keeps cap/truncation accounting exact (catalog §5: skips must not
        // bypass budgets, and a short skip on a truncated entry must not silently under-consume).
        long skipped = 0;
        byte[] scratch = new byte[8192];
        while (skipped < n) {
            int toRead = (int) Math.min(scratch.length, n - skipped);
            int r = delegate.read(scratch, 0, toRead);
            if (r < 0) {
                checkTruncation();
                break;
            }
            if (r == 0) {
                throw new IOException("No progress skipping entry content: " + entryName);
            }
            account(r);
            skipped += r;
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        if (entryLimitHit) {
            return 0;
        }
        return delegate.available();
    }

    @Override
    public void close() {
        // Deliberately a no-op: the owning archive manages the delegate's lifecycle. Closing
        // here would make the shared tar/zip stream unusable for subsequent entries.
    }

    private void account(int r) {
        totalRead += r;
        if (totalRead > entryCap) {
            entryLimitHit = true;
            throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit during read: " + entryName
                            + " (" + totalRead + " > " + entryCap + " bytes)");
        }
        if (passCharged != null) {
            long now = passCharged.addAndGet(r);
            if (now > passCap) {
                onPassExceed.run();
                throw new AnnattoException.SecurityException(
                        "Entry-stream data exceeds per-pass limit: " + entryName);
            }
        }
    }

    private void checkTruncation() throws IOException {
        if (declaredSize >= 0 && totalRead < declaredSize) {
            throw new IOException(
                    "Truncated entry content: " + entryName
                            + " (read " + totalRead + " of " + declaredSize + " bytes)");
        }
    }

    /** Total bytes delivered by this view so far. */
    public long bytesRead() {
        return totalRead;
    }
}
