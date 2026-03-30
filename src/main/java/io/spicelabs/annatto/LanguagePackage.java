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

package io.spicelabs.annatto;

import com.github.packageurl.PackageURL;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;

/**
 * Unified interface for all language packages.
 *
 * <p>Claims:
 * <ul>
 *   <li>All implementations are immutable (verified by LanguagePackageContractTest.immutableAfterConstruction)</li>
 *   <li>All methods return consistent results on multiple calls (verified by LanguagePackageContractTest)</li>
 *   <li>name() and version() never return null (verified by LanguagePackageContractTest.nameNeverNull)</li>
 *   <li>streamEntries() is NOT thread-safe - one stream per thread (verified by ThreadSafetyTest)</li>
 *   <li>All other methods are thread-safe (verified by ThreadSafetyTest)</li>
 * </ul>
 *
 * <p>Stream Lifecycle (ADR-001):
 * The stream returned by streamEntries() MUST be closed by the caller using try-with-resources.
 * Example:
 * <pre>
 * try (PackageEntryStream entries = pkg.streamEntries()) {
 *     entries.forEach(entry -&gt; process(entry));
 * }
 * </pre>
 */
public interface LanguagePackage {

    /**
     * The detected MIME type from Tika.
     *
     * <p>Test: LanguagePackageContractTest.mimeTypeNonNull</p>
     *
     * @return original Tika MIME type string, never null
     */
    @NotNull
    String mimeType();

    /**
     * The ecosystem this package belongs to.
     *
     * <p>Test: LanguagePackageContractTest.ecosystemNonNull</p>
     *
     * @return ecosystem enum, never null
     */
    @NotNull
    Ecosystem ecosystem();

    /**
     * Package name (fully qualified).
     *
     * <p>Test: LanguagePackageContractTest.nameNeverNull</p>
     *
     * @return package name, never null (may be empty if extraction failed)
     */
    @NotNull
    String name();

    /**
     * Package version.
     *
     * <p>Test: LanguagePackageContractTest.versionNeverNull</p>
     *
     * @return package version, never null (may be empty if extraction failed)
     */
    @NotNull
    String version();

    /**
     * Full package metadata.
     *
     * <p>Test: LanguagePackageContractTest.metadataNeverNull</p>
     *
     * @return metadata record, never null
     */
    @NotNull
    PackageMetadata metadata();

    /**
     * Generate Package URL (PURL) for this package.
     *
     * <p>Test: LanguagePackageContractTest.toPurlReturnsValidPurl when metadata complete</p>
     * <p>Test: LanguagePackageContractTest.toPurlReturnsEmptyWhenIncomplete when metadata incomplete</p>
     *
     * @return PURL if name and version are present and valid
     */
    @NotNull
    Optional<PackageURL> toPurl();

    /**
     * Stream the entries in this package.
     *
     * <p>Stream Lifecycle (ADR-001):
     * <ul>
     *   <li>Caller MUST close the returned stream using try-with-resources</li>
     *   <li>Stream is NOT thread-safe - use one stream per thread</li>
     *   <li>Only one stream can be open per package instance at a time</li>
     *   <li>Opening a new stream while another is open throws IllegalStateException</li>
     * </ul>
     *
     * <p>Security (ADR-005):
     * <ul>
     *   <li>Automatically enforces size limits on entries (throws SecurityException)</li>
     *   <li>Rejects path traversal attempts (entry skipped or throws)</li>
     *   <li>Limits total entries to 10,000 (throws SecurityException)</li>
     * </ul>
     *
     * <p>Test: StreamingResourceManagementTest validates all lifecycle scenarios</p>
     * <p>Test: SecurityLimitsTest validates protections</p>
     *
     * @return stream of package entries
     * @throws IOException if package cannot be read
     * @throws IllegalStateException if another stream is already open
     */
    @NotThreadSafe
    @NotNull
    PackageEntryStream streamEntries() throws IOException;

    /**
     * Close any resources associated with this package (temp files, etc).
     * Idempotent - safe to call multiple times.
     */
    void close();
}
