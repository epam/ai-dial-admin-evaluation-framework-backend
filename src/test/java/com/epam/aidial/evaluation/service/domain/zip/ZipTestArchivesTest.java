package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ZipTestArchives (test fixture self-check)")
class ZipTestArchivesTest {

    private static final byte[] DATA = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("lieAboutUncompressedSize changes only the declared size; the real bytes still decompress")
    void lieAboutUncompressedSize_declaredSizeChanges_contentIntact() throws IOException {
        byte[] zip = zip("files/1/a.bin", "files/1/b.bin");

        byte[] lying = ZipTestArchives.lieAboutUncompressedSize(zip, "files/1/b.bin", 3);

        try (ZipFile zipFile = open(lying)) {
            assertThat(zipFile.getEntry("files/1/a.bin").getSize()).isEqualTo(DATA.length);
            ZipEntry lied = zipFile.getEntry("files/1/b.bin");
            assertThat(lied.getSize()).isEqualTo(3);
            try (InputStream in = zipFile.getInputStream(lied)) {
                assertThat(in.readAllBytes()).isEqualTo(DATA);
            }
        }
    }

    @Test
    @DisplayName("lieAboutUncompressedSize fails loudly for an unknown entry")
    void lieAboutUncompressedSize_unknownEntry_throws() throws IOException {
        byte[] zip = zip("files/1/a.bin");

        assertThatThrownBy(() -> ZipTestArchives.lieAboutUncompressedSize(zip, "files/1/zzz.bin", 3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("renameEntry produces a genuine duplicate entry name")
    void renameEntry_producesDuplicateName() throws IOException {
        byte[] zip = zip("files/1/aaa.pdf", "files/1/bbb.pdf");

        byte[] renamed = ZipTestArchives.renameEntry(zip, "files/1/bbb.pdf", "files/1/aaa.pdf");

        try (ZipFile zipFile = open(renamed)) {
            List<String> names = new ArrayList<>();
            Collections.list(zipFile.entries()).forEach(e -> names.add(e.getName()));
            assertThat(names).containsExactly("files/1/aaa.pdf", "files/1/aaa.pdf");
        }
    }

    private ZipFile open(byte[] zip) throws IOException {
        Path file = Files.createTempFile(tempDir, "fixture", ".zip");
        Files.write(file, zip);
        return new ZipFile(file.toFile());
    }

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String name : names) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(DATA);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
