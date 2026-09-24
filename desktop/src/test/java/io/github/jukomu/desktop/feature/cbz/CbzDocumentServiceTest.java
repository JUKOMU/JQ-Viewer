package io.github.jukomu.desktop.feature.cbz;

import io.github.jukomu.desktop.feature.export.archive.ComicInfo;
import io.github.jukomu.desktop.feature.export.archive.ComicInfoCodec;
import io.github.jukomu.desktop.feature.files.FileReferences;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CbzDocumentServiceTest {
    @Test
    void indexesNestedImagesNaturallyAndReadsComicInfoCover() throws Exception {
        Path file = Files.createTempFile("jq-cbz-index-", ".cbz");
        byte[] comicInfo = ComicInfoCodec.serialize(new ComicInfo(
                "Chapter", "Series", "2", "Alice", "https://18comic.vip/album/123",
                3, "YesAndRightToLeft", null, null,
                List.of(new ComicInfo.Page(1, null, "FrontCover"))));
        writeArchive(file, List.of(
                entry("pages/10.webp", "ten"),
                entry(".hidden/1.jpg", "hidden"),
                entry("pages/2.png", "two"),
                entry("pages/1.jpg", "one"),
                new Entry("ComicInfo.xml", comicInfo),
                entry("notes.txt", "ignored")
        ));
        CbzDocumentService service = new CbzDocumentService();
        String fileRef = FileReferences.fileRef(file);

        CbzDocumentService.Info info = service.getInfo(fileRef);
        assertEquals(3, info.pageCount());
        assertEquals("Series", info.series());
        assertEquals("2", info.number());
        assertEquals(2, info.coverPage());
        assertEquals("https://18comic.vip/album/123", info.web());

        CbzDocumentService.PageResource resource = service.openPage(fileRef, 2);
        assertEquals("image/png", resource.mimeType());
        try (var page = resource.input()) {
            assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), page.readAllBytes());
        }
    }

    @Test
    void invalidComicInfoWarnsButEmptyArchiveIsRejected() throws Exception {
        Path readable = Files.createTempFile("jq-cbz-warning-", ".cbz");
        writeArchive(readable, List.of(
                entry("001.jpg", "image"),
                entry("ComicInfo.xml", "<broken")
        ));
        CbzDocumentService service = new CbzDocumentService();
        CbzDocumentService.Info info = service.getInfo(FileReferences.fileRef(readable));
        assertNotNull(info.metadataWarning());
        assertEquals(1, info.pageCount());

        Path empty = Files.createTempFile("jq-cbz-empty-", ".cbz");
        writeArchive(empty, List.of(entry("readme.txt", "none")));
        CbzDocumentService.CbzException error = assertThrows(
                CbzDocumentService.CbzException.class,
                () -> service.getInfo(FileReferences.fileRef(empty)));
        assertEquals("CBZ_NO_IMAGES", error.code());
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeArchive(Path file, List<Entry> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Entry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name()));
                output.write(entry.content());
                output.closeEntry();
            }
        }
    }

    private record Entry(String name, byte[] content) {
    }
}
