/*
 * Copyright 2026 Spice Labs, Inc.
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
package io.spicelabs.annatto.bomb;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.Ecosystem;
import io.spicelabs.annatto.EcosystemRouter;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fuzz harness for {@link EcosystemRouter} (Fresh Scent Phase 7, catalog §13).
 *
 * <p>Invariant: arbitrary bytes routed through content detection must TERMINATE and throw
 * only the documented exception types ({@code IOException} or the documented unchecked
 * {@code MalformedPackageException}/{@code SecurityException}). No other type, and no
 * {@code Error}. The harness does not catch RuntimeException (the §13 trap).
 */
class RouterFuzzBombTest {

    private static final Path TEMP_DIR = createTempDir();

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("annatto-router-fuzz");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Provide
    Arbitrary<byte[]> hostileBytes() {
        Arbitrary<byte[]> magic = Arbitraries.of(
                new byte[]{'P', 'K', 0x03, 0x04},                        // zip
                new byte[]{0x1F, (byte) 0x8B, 0x08},                     // gzip
                new byte[]{'B', 'Z', 'h', '9'},                          // bzip2
                "ustar".getBytes(),                                      // tar-ish
                new byte[]{'{', '"', 'a', '"', ':'});                   // json
        Arbitrary<byte[]> tail = Arbitraries.bytes().array(byte[].class).ofMaxSize(4096);
        return magic.flatMap(m -> tail.map(t -> concat(m, t)));
    }

    // Requirement: catalog §13 / plan Phase 7
    // Theory: routing hostile content must terminate and only ever throw the documented
    //         exception types — never hang, never escape an undocumented unchecked type.
    @Property(tries = 200)
    void arbitraryBytesNeverHangOrEscape(@ForAll("hostileBytes") byte[] data) throws IOException {
        Path f = TEMP_DIR.resolve("route-" + System.nanoTime() + ".dat");
        Files.write(f, data);
        try {
            EcosystemRouter.route(f);
        } catch (AnnattoException.MalformedPackageException | AnnattoException.SecurityException e) {
            // documented unchecked contracts (javadoc on route())
        } catch (IOException e) {
            // documented checked contract (corrupt archive)
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
