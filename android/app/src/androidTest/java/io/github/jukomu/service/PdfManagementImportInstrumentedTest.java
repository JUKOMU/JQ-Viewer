package io.github.jukomu.service;

import android.content.Context;
import android.graphics.pdf.PdfDocument;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.data.PdfStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PdfManagementImportInstrumentedTest {

    private static final String DB_NAME = "jq_pdf_import.db";

    private Context context;
    private final List<File> createdFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfManagementService.clearInstanceForTest();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
    }

    @After
    public void tearDown() {
        PdfManagementService.clearInstanceForTest();
        PdfStore.clearInstanceForTest();
        context.deleteDatabase(DB_NAME);
        for (File file : createdFiles) file.delete();
        createdFiles.clear();
    }

    @Test
    public void sameLocatorIsIdempotentButSameContentAtAnotherPathCanBeImported() throws Exception {
        File first = createPdf("first.pdf");
        File second = createPdf("second.pdf");
        PdfManagementService service = PdfManagementService.getInstance(context);

        JSONObject imported = service.importPdf(item(first));
        JSONObject duplicateLocator = service.importPdf(item(first));
        JSONObject secondPath = service.importPdf(item(second));

        assertEquals("imported", imported.getString("result"));
        assertEquals("already_managed", duplicateLocator.getString("result"));
        assertEquals("imported", secondPath.getString("result"));
        assertEquals(2L, PdfStore.getInstance(context).countFiles());
    }

    @Test
    public void inspectRefreshesCurrentInformationAndPhysicalDeleteRemovesFileAndRecord()
            throws Exception {
        File pdf = createPdf("delete-me.pdf");
        PdfManagementService service = PdfManagementService.getInstance(context);
        long id = service.importPdf(item(pdf)).getLong("id");

        JSONObject inspected = service.inspectFileForDeletion(id);
        assertEquals(pdf.getCanonicalPath(), inspected.getString("filePath"));
        assertEquals(1, inspected.getInt("pageCount"));
        assertTrue(inspected.getLong("fileSize") > 0L);

        JSONObject deleted = service.deleteFile(id);
        assertEquals("deleted", deleted.getString("result"));
        assertTrue(!pdf.exists());
        assertNull(PdfStore.getInstance(context).getFile(id));
    }

    @Test
    public void missingFileDeletionRemovesOnlyTheRecord() throws Exception {
        File pdf = createPdf("missing.pdf");
        PdfManagementService service = PdfManagementService.getInstance(context);
        long id = service.importPdf(item(pdf)).getLong("id");
        assertTrue(pdf.delete());

        JSONObject result = service.deleteFile(id);

        assertEquals("already_missing", result.getString("result"));
        assertNull(PdfStore.getInstance(context).getFile(id));
    }

    @Test
    public void explicitValidationMarksCorruptFileWithoutRemovingRecord() throws Exception {
        File pdf = createPdf("corrupt.pdf");
        PdfManagementService service = PdfManagementService.getInstance(context);
        long id = service.importPdf(item(pdf)).getLong("id");
        try (FileOutputStream output = new FileOutputStream(pdf, false)) {
            output.write(new byte[]{1, 2, 3});
        }

        JSONObject verified = service.verifyFile(id);

        assertEquals("invalid", verified.getString("availability"));
        assertEquals("corrupt", verified.getString("verificationStatus"));
        assertNotNull(PdfStore.getInstance(context).getFile(id));
    }

    private JSONObject item(File file) throws Exception {
        return new JSONObject()
            .put("filePath", file.getCanonicalPath())
            .put("fileName", file.getName())
            .put("albumId", "album-1")
            .put("chapterId", "chapter-1")
            .put("chapterTitle", "第一话");
    }

    private File createPdf(String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        createdFiles.add(file);
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(10, 10, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            document.finishPage(page);
            try (FileOutputStream output = new FileOutputStream(file)) {
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
        return file;
    }
}
