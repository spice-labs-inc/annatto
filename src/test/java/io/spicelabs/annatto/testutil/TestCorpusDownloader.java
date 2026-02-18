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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
 * and is non-empty.</p>
 */
public final class TestCorpusDownloader {

    private static final Logger logger = LoggerFactory.getLogger(TestCorpusDownloader.class);
    private static final String CORPUS_URL =
            "https://public-test-data.spice-labs.dev/annatto/annatto-test-corpus.tar.gz";
    private static final Path CORPUS_DIR = Path.of("test-corpus");
    private static final int MAX_RETRIES = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private TestCorpusDownloader() {
    }

    /**
     * Ensures the test corpus is available locally. Downloads and expands the corpus
     * archive from the public test data server if the {@code test-corpus/} directory
     * does not already contain files.
     *
     * @throws IOException if the download or extraction fails after all retries
     */
    public static void ensureCorpusAvailable() throws IOException {
        if (isCorpusPresent()) {
            logger.debug("Test corpus already present at {}", CORPUS_DIR);
            return;
        }

        logger.info("Downloading test corpus from {}", CORPUS_URL);
        Files.createDirectories(CORPUS_DIR);

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
     * Checks whether the test corpus directory exists and contains at least one
     * ecosystem subdirectory with files.
     *
     * @return true if the corpus is already present
     */
    public static boolean isCorpusPresent() {
        if (!Files.isDirectory(CORPUS_DIR)) {
            return false;
        }
        try (var entries = Files.list(CORPUS_DIR)) {
            return entries.anyMatch(p -> Files.isDirectory(p) && hasFiles(p));
        } catch (IOException e) {
            return false;
        }
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
     */
    private static void expandCorpus(@NotNull InputStream tgzStream) throws IOException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(tgzStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path target = CORPUS_DIR.resolve(entry.getName()).normalize();
                // Prevent path traversal
                if (!target.startsWith(CORPUS_DIR)) {
                    throw new IOException("Tar entry escapes corpus directory: " + entry.getName());
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

    private static boolean hasFiles(@NotNull Path dir) {
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }
}
