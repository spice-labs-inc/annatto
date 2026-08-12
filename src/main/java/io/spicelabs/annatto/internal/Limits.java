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

/**
 * Resource limits for archive parsing (ADR-005).
 *
 * <p>These are the per-package byte/entry budgets that protect Annatto against
 * resource-exhaustion attacks. Defaults follow the Phase 7 plan limits table:
 * spooled (compressed) package cap 1 GiB, decompressed metadata scan cap 500 MiB,
 * per-{@code streamEntries()} pass decompressed budget 1 GiB, per-pass ZIP inflated
 * budget on zip entry reads 1 GiB, per-entry cap 10 MiB, entry count 10 000.
 *
 * <p>Injected per-instance via the package-private/public {@code fromStream(...)} overloads
 * that accept a {@code Limits} argument; they replace the previous default-only behavior so
 * tests and embedders can tighten bounds without shared mutable state (ADR-004).
 */
public record Limits(
        long spoolBytes,
        long scanBytes,
        long streamPassBytes,
        long zipPassBytes,
        long entryBytes,
        int maxEntries) {

    public static final long DEFAULT_SPOOL_BYTES = 1024L * 1024 * 1024;
    public static final long DEFAULT_SCAN_BYTES = 500L * 1024 * 1024;
    public static final long DEFAULT_STREAM_PASS_BYTES = 1024L * 1024 * 1024;
    public static final long DEFAULT_ZIP_PASS_BYTES = 1024L * 1024 * 1024;
    public static final long DEFAULT_ENTRY_BYTES = 10L * 1024 * 1024;
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** Default production limits. */
    public static final Limits DEFAULT = new Limits(
            DEFAULT_SPOOL_BYTES,
            DEFAULT_SCAN_BYTES,
            DEFAULT_STREAM_PASS_BYTES,
            DEFAULT_ZIP_PASS_BYTES,
            DEFAULT_ENTRY_BYTES,
            DEFAULT_MAX_ENTRIES);

    /** Convenience constructor for tests that only override the spool bound. */
    public static Limits spool(long spoolBytes) {
        return new Limits(spoolBytes, DEFAULT_SCAN_BYTES, DEFAULT_STREAM_PASS_BYTES,
                DEFAULT_ZIP_PASS_BYTES, DEFAULT_ENTRY_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** Convenience constructor for tests that only override the decompressed scan bound. */
    public static Limits scan(long scanBytes) {
        return new Limits(DEFAULT_SPOOL_BYTES, scanBytes, DEFAULT_STREAM_PASS_BYTES,
                DEFAULT_ZIP_PASS_BYTES, DEFAULT_ENTRY_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** Convenience constructor for tests that only override the per-pass stream budget. */
    public static Limits streamPass(long streamPassBytes) {
        return new Limits(DEFAULT_SPOOL_BYTES, DEFAULT_SCAN_BYTES, streamPassBytes,
                DEFAULT_ZIP_PASS_BYTES, DEFAULT_ENTRY_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** Convenience constructor for tests that only override the per-pass ZIP inflated budget. */
    public static Limits zipPass(long zipPassBytes) {
        return new Limits(DEFAULT_SPOOL_BYTES, DEFAULT_SCAN_BYTES, DEFAULT_STREAM_PASS_BYTES,
                zipPassBytes, DEFAULT_ENTRY_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** Convenience constructor for tests that only override the per-entry cap. */
    public static Limits entry(long entryBytes) {
        return new Limits(DEFAULT_SPOOL_BYTES, DEFAULT_SCAN_BYTES, DEFAULT_STREAM_PASS_BYTES,
                DEFAULT_ZIP_PASS_BYTES, entryBytes, DEFAULT_MAX_ENTRIES);
    }

    /** Convenience constructor for tests that only override the entry-count cap. */
    public static Limits entries(int maxEntries) {
        return new Limits(DEFAULT_SPOOL_BYTES, DEFAULT_SCAN_BYTES, DEFAULT_STREAM_PASS_BYTES,
                DEFAULT_ZIP_PASS_BYTES, DEFAULT_ENTRY_BYTES, maxEntries);
    }
}