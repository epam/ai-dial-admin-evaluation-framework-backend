package com.epam.aidial.evaluation.service.domain.zip;

import java.nio.charset.StandardCharsets;

/**
 * Builds malformed ZIP archives that {@link java.util.zip.ZipOutputStream} refuses to write, by patching a
 * well-formed archive in place. Shared by the unit and functional ZIP import tests; {@code
 * ZipTestArchivesTest} checks that each patch really produces the malformation it names.
 */
public final class ZipTestArchives {

    /** Central directory file header signature {@code PK\1\2}. */
    private static final int CEN_SIGNATURE = 0x02014b50;

    private static final int CEN_UNCOMPRESSED_SIZE_OFFSET = 24;
    private static final int CEN_NAME_LENGTH_OFFSET = 28;
    private static final int CEN_NAME_OFFSET = 46;

    private ZipTestArchives() {}

    /**
     * Sets the central directory's declared uncompressed size of {@code entryName} to {@code declaredSize},
     * leaving the real (compressed) data intact: an entry whose header lies about its size, which {@link
     * java.util.zip.ZipFile#getInputStream} still decompresses in full.
     */
    public static byte[] lieAboutUncompressedSize(byte[] zip, String entryName, int declaredSize) {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] patched = zip.clone();
        for (int i = 0; i + CEN_NAME_OFFSET <= patched.length; i++) {
            if (readLe32(patched, i) == CEN_SIGNATURE
                    && readLe16(patched, i + CEN_NAME_LENGTH_OFFSET) == nameBytes.length
                    && matches(patched, i + CEN_NAME_OFFSET, nameBytes)) {
                writeLe32(patched, i + CEN_UNCOMPRESSED_SIZE_OFFSET, declaredSize);
                return patched;
            }
        }
        throw new IllegalStateException("Central directory entry not found: " + entryName);
    }

    /**
     * Renames an entry in both its local header and its central directory record, producing names {@code
     * ZipOutputStream} would reject (e.g. a duplicate). {@code from} and {@code to} must have the same
     * UTF-8 length, and {@code from} must not occur in any entry's data.
     */
    public static byte[] renameEntry(byte[] zip, String from, String to) {
        byte[] fromBytes = from.getBytes(StandardCharsets.UTF_8);
        byte[] toBytes = to.getBytes(StandardCharsets.UTF_8);
        if (fromBytes.length != toBytes.length) {
            throw new IllegalArgumentException("Names must be the same length to rename in place");
        }
        byte[] patched = zip.clone();
        int replaced = 0;
        for (int i = 0; i + fromBytes.length <= patched.length; i++) {
            if (matches(patched, i, fromBytes)) {
                System.arraycopy(toBytes, 0, patched, i, toBytes.length);
                replaced++;
            }
        }
        if (replaced == 0) {
            throw new IllegalStateException("Entry name not found: " + from);
        }
        return patched;
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] b, int off) {
        return readLe16(b, off) | (readLe16(b, off + 2) << 16);
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }
}
