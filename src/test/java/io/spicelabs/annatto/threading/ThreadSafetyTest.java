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

package io.spicelabs.annatto.threading;

import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Thread safety tests for Annatto.
 *
 * <p>Theory: LanguagePackageReader and LanguagePackage are designed to be thread-safe
 * for most operations, except stream reading which is explicitly not thread-safe (ADR-004).
 *
 * <p>Requirements tested:
 * <ul>
 *   <li>LanguagePackageReader static methods are thread-safe (stateless)</li>
 *   <li>LanguagePackage methods (except streamEntries) are thread-safe</li>
 *   <li>Concurrent reads of same package don't corrupt state</li>
 *   <li>streamEntries throws if called concurrently</li>
 * </ul>
 */
public class ThreadSafetyTest {

    private static final int CONCURRENCY = 10;
    private static final int ITERATIONS = 100;

    /**
     * Creates a synthetic npm package for testing.
     */
    private LanguagePackage createTestPackage() throws IOException, AnnattoException.MalformedPackageException {
        byte[] packageData = createSyntheticNpmPackage();
        return NpmPackage.fromStream(new ByteArrayInputStream(packageData), "test-package-1.0.0.tgz");
    }

    /**
     * Creates a minimal valid npm package (tgz archive).
     */
    private byte[] createSyntheticNpmPackage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

            String packageJson = """
                {
                  "name": "test-package",
                  "version": "1.0.0",
                  "description": "Test package for thread safety tests"
                }
                """;
            addEntry(tarOut, "package/package.json", packageJson.getBytes(StandardCharsets.UTF_8));
            addEntry(tarOut, "package/index.js", "console.log('hello');".getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    private void addEntry(TarArchiveOutputStream tarOut, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(content);
        tarOut.closeArchiveEntry();
    }

    @Test
    @DisplayName("concurrent reads of same package are safe")
    void concurrentReadsOfSamePackage() throws Exception {
        LanguagePackage pkg = createTestPackage();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);

        List<Future<Boolean>> futures = IntStream.range(0, CONCURRENCY * ITERATIONS)
            .mapToObj(i -> executor.submit(() -> {
                latch.countDown();
                latch.await(); // Synchronize start
                // Concurrent metadata access should be safe
                assertThat(pkg.name()).isEqualTo("test-package");
                assertThat(pkg.version()).isEqualTo("1.0.0");
                assertThat(pkg.ecosystem()).isEqualTo(Ecosystem.NPM);
                assertThat(pkg.metadata()).isNotNull();
                return true;
            }))
            .toList();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // All should complete without exception
        for (Future<Boolean> f : futures) {
            assertThatNoException().isThrownBy(f::get);
        }
    }

    @Test
    @DisplayName("concurrent reads of different packages are safe")
    void concurrentReadsOfDifferentPackages() throws Exception {
        // Create multiple packages
        List<LanguagePackage> packages = IntStream.range(0, CONCURRENCY)
            .mapToObj(i -> {
                try {
                    return createTestPackage();
                } catch (IOException | AnnattoException.MalformedPackageException e) {
                    throw new RuntimeException(e);
                }
            })
            .toList();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);

        List<Future<Boolean>> futures = IntStream.range(0, CONCURRENCY * ITERATIONS)
            .mapToObj(i -> {
                LanguagePackage pkg = packages.get(i % packages.size());
                return executor.submit(() -> {
                    latch.countDown();
                    latch.await();
                    assertThat(pkg.name()).isEqualTo("test-package");
                    assertThat(pkg.metadata().name()).isEqualTo("test-package");
                    return true;
                });
            })
            .toList();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<Boolean> f : futures) {
            assertThatNoException().isThrownBy(f::get);
        }
    }

    @Test
    @DisplayName("concurrent calls to isSupported are safe")
    void concurrentIsSupportedCalls() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);

        List<Future<Boolean>> futures = IntStream.range(0, CONCURRENCY * ITERATIONS)
            .mapToObj(i -> executor.submit(() -> {
                latch.countDown();
                latch.await(); // Synchronize start
                return LanguagePackageReader.isSupported("application/zip");
            }))
            .toList();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // All should return true (no exceptions, consistent results)
        for (Future<Boolean> f : futures) {
            assertThatNoException().isThrownBy(() -> {
                assertThat(f.get()).isTrue();
            });
        }
    }

    @Test
    @DisplayName("repeated calls to metadata return same result")
    void repeatedMetadataCallsConsistent() throws IOException, AnnattoException.MalformedPackageException {
        LanguagePackage pkg = createTestPackage();

        // Multiple calls should return the same values
        for (int i = 0; i < 100; i++) {
            assertThat(pkg.name()).isEqualTo("test-package");
            assertThat(pkg.version()).isEqualTo("1.0.0");
            assertThat(pkg.metadata().name()).isEqualTo("test-package");
            assertThat(pkg.metadata().version()).isEqualTo("1.0.0");

            Optional<PackageURL> purl = pkg.toPurl();
            assertThat(purl).isPresent();
            assertThat(purl.get().toString()).isEqualTo("pkg:npm/test-package@1.0.0");
        }
    }

    @Test
    @DisplayName("streamEntries is NOT thread-safe - throws on concurrent access")
    void streamEntriesNotThreadSafe() throws IOException, AnnattoException.MalformedPackageException {
        LanguagePackage pkg = createTestPackage();

        PackageEntryStream stream1 = pkg.streamEntries();
        try {
            // Opening another stream while first is open should throw
            assertThatThrownBy(() -> pkg.streamEntries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        } finally {
            stream1.close();
        }

        // After closing first stream, should be able to open another
        try (PackageEntryStream stream2 = pkg.streamEntries()) {
            assertThat(stream2.hasNext()).isTrue();
        }
    }

    @Test
    @DisplayName("LanguagePackage methods are consistent under concurrent access")
    void packageMethodsConsistentUnderConcurrentAccess() throws Exception {
        LanguagePackage pkg = createTestPackage();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);

        // Mix of different method calls
        List<Future<Boolean>> futures = IntStream.range(0, CONCURRENCY * ITERATIONS)
            .mapToObj(i -> executor.submit(() -> {
                latch.countDown();
                latch.await();
                switch (i % 5) {
                    case 0 -> assertThat(pkg.name()).isEqualTo("test-package");
                    case 1 -> assertThat(pkg.version()).isEqualTo("1.0.0");
                    case 2 -> assertThat(pkg.ecosystem()).isEqualTo(Ecosystem.NPM);
                    case 3 -> assertThat(pkg.metadata()).isNotNull();
                    case 4 -> assertThat(pkg.toPurl()).isPresent();
                }
                return true;
            }))
            .toList();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<Boolean> f : futures) {
            assertThatNoException().isThrownBy(f::get);
        }
    }

    @Test
    @DisplayName("no state leakage between different package instances")
    void noStateLeakageBetweenInstances() throws Exception {
        // Create multiple distinct packages
        LanguagePackage pkg1 = NpmPackage.fromStream(
            new ByteArrayInputStream(createSyntheticNpmPackageWithName("package-one", "1.0.0")),
            "package-one-1.0.0.tgz");
        LanguagePackage pkg2 = NpmPackage.fromStream(
            new ByteArrayInputStream(createSyntheticNpmPackageWithName("package-two", "2.0.0")),
            "package-two-2.0.0.tgz");

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);

        // Concurrently access different packages
        List<Future<Boolean>> futures = IntStream.range(0, CONCURRENCY * ITERATIONS)
            .mapToObj(i -> executor.submit(() -> {
                latch.countDown();
                latch.await();
                if (i % 2 == 0) {
                    assertThat(pkg1.name()).isEqualTo("package-one");
                    assertThat(pkg1.version()).isEqualTo("1.0.0");
                } else {
                    assertThat(pkg2.name()).isEqualTo("package-two");
                    assertThat(pkg2.version()).isEqualTo("2.0.0");
                }
                return true;
            }))
            .toList();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        for (Future<Boolean> f : futures) {
            assertThatNoException().isThrownBy(f::get);
        }
    }

    private byte[] createSyntheticNpmPackageWithName(String name, String version) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos, StandardCharsets.UTF_8.name())) {

            String packageJson = String.format("""
                {
                  "name": "%s",
                  "version": "%s"
                }
                """, name, version);
            addEntry(tarOut, "package/package.json", packageJson.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}
