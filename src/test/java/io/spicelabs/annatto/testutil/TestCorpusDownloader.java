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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the test corpus archive from the public test data server and expands it
 * into the {@code test-corpus/} directory at the project root.
 *
 * <p>The corpus is a single {@code annatto-test-corpus.tar.gz} hosted at
 * {@code https://public-test-data.spice-labs.dev/annatto/annatto-test-corpus.tar.gz}.
 * It contains per-ecosystem subdirectories (e.g., {@code npm/}, {@code pypi/}) with
 * the actual package files used for testing.</p>
 *
 * <p>Source-of-truth expected JSON files are stored separately in
 * {@code src/test/resources/<ecosystem>/} and checked into git.</p>
 *
 * <p>The download is skipped if the {@code test-corpus/} directory already exists
 * and contains at least {@value #MINIMUM_FILE_COUNT} files. File locking is used
 * to prevent concurrent downloads from multiple JVMs or threads.</p>
 *
 * <p>LLM-Friendly Section:
 * <ul>
 *   <li><b>Security:</b> Symlinks are blocked during both file counting and tar
 *       extraction. Path traversal attacks via tar entries are detected and blocked.</li>
 *   <li><b>Concurrency:</b> FileChannel.lock() provides exclusive access during
 *       download to prevent TOCTOU race conditions between parallel test processes.</li>
 *   <li><b>Threshold:</b> MINIMUM_FILE_COUNT (300) ensures partial downloads are
 *       detected and re-downloaded.</li>
 *   <li><b>Safety Limits:</b> MAX_FILES_TO_COUNT prevents resource exhaustion from
 *       circular symlinks or runaway directory traversal.</li>
 * </ul>
 */
public final class TestCorpusDownloader {

    private static final Logger logger = LoggerFactory.getLogger(TestCorpusDownloader.class);
    private static final String CORPUS_URL =
            "https://public-test-data.spice-labs.dev/annatto/annatto-test-corpus.tar.gz";

    // Allow overriding for tests via reflection or package-private setter
    private static Path CORPUS_DIR = Path.of("test-corpus");
    private static final int MAX_RETRIES = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * Minimum number of files required for corpus to be considered complete.
     * Requirement: Plan Section 1 - File count threshold
     */
    static final int MINIMUM_FILE_COUNT = 300;

    /**
     * Upper bound on files to count to prevent resource exhaustion.
     * Requirement: Plan Section 1 - Safety limit
     */
    static final long MAX_FILES_TO_COUNT = 10000;

    /**
     * Lock file for preventing concurrent downloads.
     * Requirement: Plan Section 1 - File locking
     */
    private static final String LOCK_FILENAME = ".download.lock";

    private TestCorpusDownloader() {
    }

    /**
     * Ensures the test corpus is available locally. Downloads and expands the corpus
     * archive from the public test data server if the {@code test-corpus/} directory
     * does not already contain at least {@value #MINIMUM_FILE_COUNT} files.
     *
     * <p>Uses file locking to prevent concurrent downloads from multiple JVMs or threads.
     * After acquiring the lock, a double-check ensures another process didn't complete
     * the download while waiting for the lock.
     *
     * @throws IOException if the download or extraction fails after all retries
     */
    public static void ensureCorpusAvailable() throws IOException {
        // Quick check without locking (fast path)
        if (isCorpusPresent()) {
            logger.debug("Test corpus already present at {}", CORPUS_DIR);
            return;
        }

        // Create corpus directory if needed before attempting to lock
        Files.createDirectories(CORPUS_DIR);

        // Acquire lock to prevent concurrent downloads
        Path lockFile = CORPUS_DIR.resolve(LOCK_FILENAME);
        try (RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {

            // Double-check after acquiring lock (TOCTOU protection)
            if (isCorpusPresent()) {
                logger.debug("Test corpus present after acquiring lock at {}", CORPUS_DIR);
                return;
            }

            logger.info("Downloading test corpus from {}", CORPUS_URL);
            performDownload();
        }
    }

    /**
     * Performs the actual HTTP download with retry logic.
     * Must be called while holding the file lock.
     */
    private static void performDownload() throws IOException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build()) {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(CORPUS_URL))
                            .timeout(TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<InputStream> response = client.send(request,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        try (InputStream body = response.body()) {
                            expandCorpus(body);
                        }
                        logger.info("Test corpus downloaded and expanded (attempt {})", attempt);
                        return;
                    }
                    logger.warn("HTTP {} downloading corpus (attempt {})", response.statusCode(), attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Corpus download interrupted", e);
                } catch (IOException e) {
                    logger.warn("Failed to download corpus (attempt {}): {}", attempt, e.getMessage());
                    if (attempt == MAX_RETRIES) {
                        throw new IOException("Failed to download corpus after " + MAX_RETRIES + " attempts", e);
                    }
                }
            }
        }
        throw new IOException("Failed to download test corpus from " + CORPUS_URL);
    }

    /**
     * Checks whether the test corpus directory exists and contains at least
     * {@value #MINIMUM_FILE_COUNT} files.
     *
     * <p>Uses secure file counting that skips symlinks to prevent directory escape.
     *
     * @return true if the corpus is already present with sufficient files
     */
    public static boolean isCorpusPresent() {
        if (!Files.isDirectory(CORPUS_DIR)) {
            return false;
        }
        try {
            long fileCount = countFilesRecursively(CORPUS_DIR);
            return fileCount >= MINIMUM_FILE_COUNT;
        } catch (IOException e) {
            logger.warn("Error counting files in corpus directory: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Counts regular files recursively in a directory, skipping symlinks.
     *
     * <p>Security: Uses {@link Files#walkFileTree} instead of {@link Files#walk}
     * to avoid following symlinks. Both symlinked files and symlinked directories
     * are skipped to prevent directory escape attacks.
     *
     * <p>Safety: Stops counting at {@value #MAX_FILES_TO_COUNT} to prevent
     * resource exhaustion from circular symlinks or extremely large directories.
     *
     * @param dir the directory to traverse
     * @return the number of regular files found
     * @throws IOException if an I/O error occurs during traversal
     */
    static long countFilesRecursively(Path dir) throws IOException {
        AtomicLong count = new AtomicLong(0);
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Skip symlinks - only count regular files
                // This prevents counting files outside the corpus via symlinks
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    if (count.incrementAndGet() > MAX_FILES_TO_COUNT) {
                        logger.warn("File count exceeded maximum limit ({}), terminating traversal",
                                MAX_FILES_TO_COUNT);
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) {
                // Skip symlinked directories to prevent escaping corpus directory
                // This blocks traversal into symlinked subdirectories
                if (Files.isSymbolicLink(subdir)) {
                    logger.debug("Skipping symlinked directory: {}", subdir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.debug("Failed to visit file {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        return count.get();
    }

    /**
     * Returns the path to the test corpus directory for a given ecosystem.
     * Package files (.tgz, .whl, .crate, etc.) are stored here.
     *
     * @param ecosystem the ecosystem name (e.g., "npm", "pypi")
     * @return the path to the ecosystem's corpus directory
     */
    public static @NotNull Path corpusDir(@NotNull String ecosystem) {
        return CORPUS_DIR.resolve(ecosystem);
    }

    /**
     * Returns the path to the expected JSON directory for a given ecosystem.
     * Source-of-truth JSON files are stored here and checked into git.
     *
     * @param ecosystem the ecosystem name (e.g., "npm", "pypi")
     * @return the path to the ecosystem's expected JSON directory
     */
    public static @NotNull Path expectedDir(@NotNull String ecosystem) {
        return Path.of("src", "test", "resources", ecosystem);
    }

    /**
     * Computes the SHA-256 hex digest of a file.
     *
     * @param file the file to hash
     * @return the lowercase hex SHA-256 digest
     * @throws IOException if the file cannot be read
     */
    public static @NotNull String sha256(@NotNull Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Expands the corpus tar.gz archive into the {@code test-corpus/} directory.
     *
     * <p>Security measures:
     * <ul>
     *   <li>Path traversal: Each entry path is normalized and verified to be within
     *       the corpus directory. Entries with '..' that escape are rejected with
     *       a {@link SecurityException}.</li>
     *   <li>Symlinks: Symbolic links in the tar archive are detected via
     *       {@link TarArchiveEntry#isSymbolicLink()} and skipped.</li>
     *   <li>Hard links: Hard links are detected via {@link TarArchiveEntry#isLink()}
     *       and skipped to prevent linking to files outside the corpus.</li>
     * </ul>
     *
     * @param tgzStream the tar.gz archive input stream
     * @throws IOException if extraction fails
     * @throws SecurityException if a path traversal attack is detected
     */
    static void expandCorpus(@NotNull InputStream tgzStream) throws IOException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(tgzStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                // Block symlinks and hard links for security
                // Symlinks can be used for directory escape attacks
                // Hard links can link to arbitrary files on the system
                if (entry.isSymbolicLink()) {
                    logger.warn("Skipping symlink in tar archive: {} -> {}",
                            entry.getName(), entry.getLinkName());
                    continue;
                }
                if (entry.isLink()) {
                    logger.warn("Skipping hard link in tar archive: {} -> {}",
                            entry.getName(), entry.getLinkName());
                    continue;
                }

                Path target = CORPUS_DIR.resolve(entry.getName()).normalize();

                // Prevent path traversal attacks
                // After normalization, target must still be within CORPUS_DIR
                if (!target.startsWith(CORPUS_DIR)) {
                    throw new SecurityException("Path traversal detected in tar entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tarIn, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // ====================================================================================
    // TEST-ONLY METHODS (package-private)
    // ====================================================================================

    /**
     * Sets the corpus directory for testing. Allows tests to use temporary directories.
     *
     * @param dir the temporary directory to use as corpus root
     */
    static void setCorpusDirForTesting(Path dir) {
        CORPUS_DIR = dir;
    }

    /**
     * Resets the corpus directory to the default after testing.
     */
    static void resetCorpusDirForTesting() {
        CORPUS_DIR = Path.of("test-corpus");
    }

    /**
     * Test-only accessor for countFilesRecursively.
     *
     * @param dir the directory to count files in
     * @return the number of regular files
     * @throws IOException if traversal fails
     */
    static long countFilesRecursivelyForTesting(Path dir) throws IOException {
        return countFilesRecursively(dir);
    }

    /**
     * Test-only accessor for expandCorpus.
     *
     * @param tgzStream the tar.gz input stream
     * @throws IOException if extraction fails
     */
    static void expandCorpusForTesting(InputStream tgzStream) throws IOException {
        expandCorpus(tgzStream);
    }
}
