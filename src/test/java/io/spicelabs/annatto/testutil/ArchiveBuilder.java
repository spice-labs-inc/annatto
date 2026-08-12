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

import io.airlift.compress.zstd.ZstdOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds in-memory archive fixtures (tar, gz, bz2, zip, conda-v2) for Annatto tests.
 *
 * <p>Replaces the ad-hoc {@code addTarEntry}/{@code writeTarEntry} helpers previously
 * duplicated across the security tests so the Phase 7 red/green fixtures are shared and
 * reviewable. Entry ORDER in the produced archive follows the argument order, which
 * matters for the decompression-budget tests (an oversized entry must precede the
 * metadata marker).
 */
public final class ArchiveBuilder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ArchiveBuilder() {
    }

    /** A regular tar entry. */
    public record Entry(String name, byte[] content, String linkTarget) {

        public Entry(String name, byte[] content) {
            this(name, content, null);
        }

        public static Entry of(String name, String content) {
            return new Entry(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static Entry of(String name, byte[] content) {
            return new Entry(name, content);
        }

        public static Entry directory(String name) {
            // TarArchiveEntry treats a trailing '/' as a directory (typeflag '5').
            return new Entry(name.endsWith("/") ? name : name + "/", new byte[0]);
        }

        /** A tar symlink entry (no data; resolved via linkTarget). */
        public static Entry symlink(String name, String target) {
            return new Entry(name, new byte[0], target);
        }

        /** A large highly-compressible entry (zeros) for decompression-bomb fixtures. */
        public static Entry zeros(String name, long size) throws IOException {
            return new Entry(name, repeat((byte) 0, size));
        }

        /** A large incompressible entry for compressed-size fixtures. */
        public static Entry random(String name, long size) {
            byte[] data = new byte[(int) size];
            RANDOM.nextBytes(data);
            return new Entry(name, data);
        }
    }

    private static byte[] repeat(byte value, long size) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(size, Integer.MAX_VALUE))) {
            byte[] chunk = new byte[8192];
            java.util.Arrays.fill(chunk, value);
            long remaining = size;
            while (remaining > 0) {
                int toWrite = (int) Math.min(chunk.length, remaining);
                baos.write(chunk, 0, toWrite);
                remaining -= toWrite;
            }
            return baos.toByteArray();
        }
    }

    /** Build a GZIP-compressed TAR archive. */
    public static byte[] gzipTar(Entry... entries) throws IOException {
        return gzipTar(java.util.List.of(entries));
    }

    public static byte[] gzipTar(java.util.List<Entry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            writeTar(gzos, entries);
        }
        return baos.toByteArray();
    }

    /** Build a plain (uncompressed) TAR archive. */
    public static byte[] plainTar(Entry... entries) throws IOException {
        return plainTar(java.util.List.of(entries));
    }

    public static byte[] plainTar(java.util.List<Entry> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeTar(baos, entries);
        return baos.toByteArray();
    }

    /** Build a BZIP2-compressed TAR archive. */
    public static byte[] bzip2Tar(Entry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzos = new BZip2CompressorOutputStream(baos)) {
            writeTar(bzos, java.util.List.of(entries));
        }
        return baos.toByteArray();
    }

    /** Build a ZSTD-compressed TAR archive. */
    public static byte[] zstdTar(Entry... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZstdOutputStream zos = new ZstdOutputStream(baos)) {
            writeTar(zos, java.util.List.of(entries));
        }
        return baos.toByteArray();
    }

    private static void writeTar(java.io.OutputStream out, java.util.List<Entry> entries) throws IOException {
        try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(out,
                java.nio.charset.StandardCharsets.UTF_8.name())) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Entry e : entries) {
                if (e.linkTarget() != null) {
                    TarArchiveEntry entry = new TarArchiveEntry(e.name(), TarArchiveEntry.LF_SYMLINK);
                    entry.setLinkName(e.linkTarget());
                    tarOut.putArchiveEntry(entry);
                    tarOut.closeArchiveEntry();
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(e.name());
                    entry.setSize(e.content().length);
                    tarOut.putArchiveEntry(entry);
                    tarOut.write(e.content());
                    tarOut.closeArchiveEntry();
                }
            }
        }
    }

    /** Build a ZIP archive from {@code name -> content}. */
    public static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setSize(e.getValue().length);
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Build a Conda v2 (.conda) package: a ZIP containing a single {@code info-*.tar.zst}.
     *
     * @param innerTarZst the zstd-compressed tar bytes to store inside the zip
     * @param zipEntryName the zip entry name (e.g., {@code info-name-1.0-0.tar.zst})
     */
    public static byte[] condaV2(byte[] innerTarZst, String zipEntryName) throws IOException {
        return zip(Map.of(zipEntryName, innerTarZst));
    }
}