package io.github.jukomu.feature.cbz;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.feature.pdf.data.PdfRef;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class CbzDocumentServiceInstrumentedTest {
    private Context context;
    private final List<File> createdFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CbzDocumentService.clearInstanceForTest();
        clearCache();
    }

    @After
    public void tearDown() {
        CbzDocumentService.clearInstanceForTest();
        for (File file : createdFiles) file.delete();
        createdFiles.clear();
        clearCache();
    }

    @Test
    public void indexesImagesNaturallyAndReadsComicInfoCover() throws Exception {
        File cbz = createArchive("chapter.cbz", false);
        String fileRef = PdfRef.createPathFileRef(cbz.getCanonicalPath());
        CbzDocumentService service = CbzDocumentService.getInstance(context);

        CbzDocumentService.Info info = service.getInfo(fileRef);

        assertEquals(3, info.pageCount);
        assertEquals("测试漫画", info.series);
        assertEquals("2", info.number);
        assertEquals(2, info.coverPage);
        assertEquals("https://18comic.vip/album/123456", info.web);
        CbzDocumentService.ValidationReport report = service.validate(fileRef, 3);
        assertEquals(3, report.pageCount);
        assertTrue(report.fileSize > 0L);

        CbzDocumentService.PageResource page = service.openPage(fileRef, 2);
        assertEquals("image/png", page.mimeType);
        try (InputStream input = page.input) {
            assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), readAll(input));
        }
    }

    @Test
    public void readsSafArchivesThroughControlledCache() throws Exception {
        String fileRef = PdfRef.createSafFileRef(
            "content://io.github.jukomu.test.pdf/document/fixture-cbz");
        CbzDocumentService service = CbzDocumentService.getInstance(context);

        CbzDocumentService.Info info = service.getInfo(fileRef);
        assertEquals(2, info.pageCount);
        assertEquals("SAF CBZ", info.title);

        CbzDocumentService.PageResource page = service.openPage(fileRef, 2);
        try (InputStream input = page.input) {
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), readAll(input));
        }
    }

    @Test
    public void damagedMetadataWarnsAndArchivesWithoutImagesAreRejected() throws Exception {
        File damagedMetadata = createArchive("damaged-metadata.cbz", true);
        CbzDocumentService.Info info = CbzDocumentService.getInstance(context).getInfo(
            PdfRef.createPathFileRef(damagedMetadata.getCanonicalPath()));
        assertNotNull(info.metadataWarning);

        File empty = new File(context.getCacheDir(), "empty-" + System.nanoTime() + ".cbz");
        createdFiles.add(empty);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(empty))) {
            put(output, "readme.txt", "none");
        }
        try {
            CbzDocumentService.getInstance(context).getInfo(
                PdfRef.createPathFileRef(empty.getCanonicalPath()));
            fail("Expected CBZ_NO_IMAGES");
        } catch (CbzDocumentService.CbzException error) {
            assertEquals("CBZ_NO_IMAGES", error.code);
        }
    }

    private File createArchive(String name, boolean damagedMetadata) throws Exception {
        File file = new File(context.getCacheDir(), System.nanoTime() + "-" + name);
        createdFiles.add(file);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            put(output, "pages/10.webp", "ten");
            put(output, "pages/2.png", "two");
            put(output, "pages/1.jpg", "one");
            put(output, ".hidden/0.jpg", "hidden");
            put(output, "ComicInfo.xml", damagedMetadata ? "<broken" :
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ComicInfo><Title>第二话</Title><Series>测试漫画</Series>"
                    + "<Number>2</Number><Writer>Alice</Writer>"
                    + "<Web>https://18comic.vip/album/123456</Web>"
                    + "<Pages><Page Image=\"1\" Type=\"FrontCover\"/></Pages>"
                    + "</ComicInfo>");
        }
        return file;
    }

    private void clearCache() {
        File directory = new File(context.getCacheDir(), "cbz-archives");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    private static void put(ZipOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
