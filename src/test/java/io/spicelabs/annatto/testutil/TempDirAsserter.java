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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that Annatto does not leave {@code annatto-*} residue in the system temp dir.
 *
 * <p>Phase 7 makes package/archive data flow through bounded spooling to temp files that
 * MUST be deleted on success, failure, and close. Any leftover {@code annatto-*} file is a
 * resource leak.
 *
 * <p>The checks are SNAPSHOT-DIFF based: {@link #snapshotAnnattoFiles()} records a baseline
 * before an operation and {@link #assertNoNewFilesSince(List)} asserts only that no NEW
 * {@code annatto-*} file appeared afterwards. This is robust against unrelated pre-existing
 * residue from other classes in the same JVM (e.g. packages whose {@code close()} was never
 * called by a legacy test) and against Genuine leftover files from a previous run. The
 * Phase-7b spool directory itself is not created by these tests, so the baseline stays clean.
 */
public final class TempDirAsserter {

    private TempDirAsserter() {
    }

    /** Snapshot the {@code annatto-*} files currently present in {@code java.io.tmpdir}. */
    public static List<Path> snapshotAnnattoFiles() throws IOException {
        List<Path> found = new ArrayList<>();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        // Root-level annatto-* files, plus any file inside the private spool dirs
        // (".annatto-private-*/annatto-spool-*") so leaks inside the spool dir are visible.
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(tmp, "annatto-*")) {
            for (Path p : dir) {
                found.add(p);
            }
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(tmp, ".annatto-private-*")) {
            for (Path privateDir : dir) {
                try (DirectoryStream<Path> inner = Files.newDirectoryStream(privateDir, "annatto-*")) {
                    for (Path p : inner) {
                        found.add(p);
                    }
                } catch (IOException e) {
                    // dir vanished concurrently; best effort
                }
            }
        }
        return found;
    }

    /**
     * Assert that no {@code annatto-*} file appeared since {@code before} was snapshotted.
     */
    public static void assertNoNewFilesSince(List<Path> before) throws IOException {
        List<Path> after = snapshotAnnattoFiles();
        List<Path> newFiles = new ArrayList<>(after);
        newFiles.removeAll(before);

        assertThat(newFiles)
                .as("expected no new annatto-* temp files in %s; new: %s",
                        System.getProperty("java.io.tmpdir"), newFiles)
                .isEmpty();
    }
}