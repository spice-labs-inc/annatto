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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide in-flight spool byte budget (ADR-005 / Phase 7).
 *
 * <p>Every package that spools archive data to disk charges this budget incrementally at
 * write time (admission-time charging) and releases it when the spooled file is deleted.
 * This stops N concurrent packages from exhausting the temp filesystem before per-package
 * caps fire. Exceeding the aggregate cap fails closed with
 * {@link AnnattoException.SecurityException}.
 *
 * <p>This is the single sanctioned mutable-static resource guard in Annatto (ADR-004
 * amendment); it is injectable for tests via {@link #overrideForTesting}.
 */
public final class AggregateSpoolBudget {

    private final long maxBytes;
    private final AtomicLong used = new AtomicLong();

    public AggregateSpoolBudget(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** Charge {@code n} bytes; throws SecurityException if the aggregate cap would be exceeded. */
    public void charge(long n) throws java.io.IOException {
        if (n <= 0) {
            return;
        }
        long now;
        // loop-free fast path; rollback if overshoot
        long before = used.addAndGet(n);
        if (before > maxBytes) {
            used.addAndGet(-n);
            throw new AnnattoException.SecurityException(
                "Aggregate spool budget exceeded (max " + maxBytes + " bytes)");
        }
    }

    /** Release {@code n} bytes (idempotent-safe, clamped to zero). */
    public void release(long n) {
        if (n <= 0) {
            return;
        }
        used.accumulateAndGet(n, (cur, dec) -> Math.max(0, cur - dec));
    }

    /** Total bytes currently charged (for tests/inspection). */
    public long used() {
        return used.get();
    }

    public long maxBytes() {
        return maxBytes;
    }
}