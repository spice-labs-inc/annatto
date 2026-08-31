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
import java.io.OutputStream;
import java.lang.ref.Cleaner;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded on-disk spooling of archive streams (Phase 7, ADR-002/ADR-005).
 *
 * <p>Spooled files live in a private per-process directory (load-bearing control: a fresh
 * {@code Files.createTempDirectory} yields 0700 under any umask). Each file is created with
 * random names (no attacker-controlled components), chmod 0600 as defense-in-depth, and is
 * cleaned up:
 * <ul>
 *   <li>immediately on construction failure (the caller's responsibility via try/finally),</li>
 *   <li>on {@code close()} of the owning package (S-5),</li>
 *   <li>via a {@link Cleaner} cleanable registered on the {@link Path} when the package
 *       becomes unreachable (GC-backstop).</li>
 * </ul>
 *
 * <p>The copy is bounded by {@code maxBytes} (spool cap) and charges the process-wide
 * {@link AggregateSpoolBudget} incrementally at write time.
 */
public final class Spool {

    private static final String PRIVATE_DIR_PREFIX = ".annatto-private-";
    private static final String FILE_PREFIX = "annatto-spool-";
    private static final String FILE_SUFFIX = ".pkg";
    private static final Set<PosixFilePermission> PRIVATE_PERMS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Cleaner CLEANER = Cleaner.create();
    private static final java.util.concurrent.ConcurrentHashMap<Path, Boolean> RELEASED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicReference<Path> PRIVATE_DIR = new AtomicReference<>();
    private static final Object PRIVATE_DIR_LOCK = new Object();
    private static final AtomicReference<AggregateSpoolBudget> BUDGET =
            new AtomicReference<>(new AggregateSpoolBudget(4L * 1024 * 1024 * 1024));

    private Spool() {
    }

    /**
     * Spool an entire stream to a private temp file, bounded by {@code maxBytes}.
     *
     * <p>Admission-time accounting: the full {@code maxBytes} budget is RESERVED against the
     * process aggregate BEFORE the copy begins (so an over-budget spool fails before eating
     * temp space), the unused reserve is released on success, and the bytes actually written
     * remain charged until the spooled file is deleted.
     *
     * @param src the source stream (fully consumed)
     * @param filename the caller-supplied name, used only in error messages
     * @param maxBytes cap on bytes copied (SecurityException when exceeded)
     * @return the spooled file plus the bytes charged to the aggregate budget
     * @throws IOException if I/O fails
     */
    public static Spooled create(InputStream src, String filename, long maxBytes) throws IOException {
        Path dir = privateDir();
        Path file = Files.createTempFile(dir, FILE_PREFIX, FILE_SUFFIX);
        try {
            applyPrivatePerms(file);
        } catch (IOException permsFailure) {
            // 0700 dir is the load-bearing control; perms failure is cosmetic.
        }

        // ADMISSION-TIME RESERVE: fail closed before any bytes hit disk if the aggregate
        // budget cannot accommodate this spool at all.
        budget().charge(maxBytes);

        long copied = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = src.read(buffer)) != -1) {
                copied += read;
                if (copied > maxBytes) {
                    throw new AnnattoException.SecurityException(
                        "Spooled package exceeds size limit: " + filename +
                        " (" + copied + " > " + maxBytes + " bytes)");
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            // AnnattoException.SecurityException is an IOException subtype (checked since
            // Fresh Scent): a limit violation lands here and propagates as declared.
            budget().release(maxBytes); // release the full reservation
            deleteQuietly(file);
            throw e;
        }

        // Release the reserved-but-unused headroom; the actually-written bytes (`copied`)
        // remain charged until the file is deleted.
        budget().release(maxBytes - copied);
        return new Spooled(file, copied);
    }

    /** A spooled file plus the number of bytes it charged to the aggregate budget. */
    public record Spooled(Path path, long chargedBytes) {
    }

    /**
     * Register a GC-time cleanup for a spooled file (idempotent with explicit delete).
     */
    public static Cleanup registration(Path spooledFile, long chargedBytes, AtomicBoolean alreadyDeleted) {
        AggregatedCleanup cleanup = new AggregatedCleanup(spooledFile, chargedBytes, alreadyDeleted);
        Cleaner.Cleanable cleanable = CLEANER.register(spooledFile, cleanup);
        return new Cleanup(cleanable, cleanup);
    }

    /**
     * Delete a spooled file now and release its budget charge (idempotent per path).
     */
    public static void delete(Spooled spooled) {
        releaseOnce(spooled.path(), spooled.chargedBytes());
    }

    /** Handle combining an explicit {@link Cleanable} with the cleanup action it runs. */
    public record Cleanup(Cleaner.Cleanable cleanable, Runnable action) {
        /** Delete now, idempotent (runs the delete + budget release exactly once). */
        public void run() {
            action.run();
        }
    }

    /** Cleanup action shared by explicit close() and GC; release is once-per-path. */
    private static final class AggregatedCleanup implements Runnable {
        private final Path file;
        private final long chargedBytes;
        private final AtomicBoolean deleted;

        AggregatedCleanup(Path file, long chargedBytes, AtomicBoolean deleted) {
            this.file = file;
            this.chargedBytes = chargedBytes;
            this.deleted = deleted;
        }

        @Override
        public void run() {
            if (deleted.compareAndSet(false, true)) {
                releaseOnce(file, chargedBytes);
            }
        }
    }

    /** Release the budget charge and delete the spool file exactly once, per file path. */
    private static void releaseOnce(Path file, long chargedBytes) {
        if (RELEASED.putIfAbsent(file, Boolean.TRUE) == null) {
            budget().release(chargedBytes);
            deleteQuietly(file);
        }
    }

    /** The private per-process spool directory (0700, random name). */
    public static Path privateDir() throws IOException {
        Path existing = PRIVATE_DIR.get();
        if (existing != null) {
            return existing;
        }
        synchronized (PRIVATE_DIR_LOCK) {
            Path again = PRIVATE_DIR.get();
            if (again != null) {
                return again;
            }
            // Phase 7 (fix 19): a fresh JVM has no live spools, so any spooled file left in the
            // tmpdir is stale (previous JVM exited uncleanly, SIGKILL/OOM). Sweep it once so the
            // resource-exhaustion class this phase defends against cannot accumulate across runs.
            sweepStaleSpools();
            Path base = Path.of(System.getProperty("java.io.tmpdir"));
            Path dir = Files.createTempDirectory(base, PRIVATE_DIR_PREFIX);
            Path published = PRIVATE_DIR.compareAndSet(null, dir) ? dir : PRIVATE_DIR.get();
            return published;
        }
    }

    private static void sweepStaleSpools() {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, PRIVATE_DIR_PREFIX + "*")) {
            for (Path d : dirs) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(d, FILE_PREFIX + "*")) {
                    for (Path f : files) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException e) {
                    // ignore per-dir errors; best effort
                }
                try {
                    Files.deleteIfExists(d);
                } catch (IOException e) {
                    // dir not empty (foreign file); leave it
                }
            }
        } catch (IOException e) {
            // base dir unreadable; best effort
        }
    }

    private static void applyPrivatePerms(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PRIVATE_PERMS);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: rely on the 0700 dir.
        }
    }

    /** TEST hook: swap the process spool budget; restore via {@link BudgetHandle#close()}. */
    public static BudgetHandle overrideBudgetForTesting(AggregateSpoolBudget budget) {
        AggregateSpoolBudget previous = BUDGET.getAndSet(budget);
        AtomicBoolean restored = new AtomicBoolean(false);
        return new BudgetHandle() {
            @Override
            public void restore() {
                if (restored.compareAndSet(false, true)) {
                    BUDGET.set(previous);
                }
            }
        };
    }

    public static AggregateSpoolBudget budget() {
        return BUDGET.get();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // best effort
        }
    }

    /** Budget-restoration handle (see {@link #overrideBudgetForTesting}). */
    public interface BudgetHandle extends AutoCloseable {
        void restore();

        @Override
        default void close() {
            restore();
        }
    }
}