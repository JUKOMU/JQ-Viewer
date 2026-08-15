package io.github.jukomu.feature.pdf.management;

import android.content.Context;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class PdfFileValidatorInstrumentedTest {

    private Context context;
    private File pdfFile;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pdfFile = new File(context.getCacheDir(), "pdf-validator-test.pdf");
        PdfDocument document = new PdfDocument();
        try {
            for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    100, 100, pageNumber).create();
                PdfDocument.Page page = document.startPage(pageInfo);
                document.finishPage(page);
            }
            try (FileOutputStream output = new FileOutputStream(pdfFile)) {
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
    }

    @After
    public void tearDown() {
        if (pdfFile != null) pdfFile.delete();
    }

    @Test
    public void validatesFilePathAndContentUri() throws Exception {
        PdfFileValidator.Report fileReport = PdfFileValidator.validate(
            context, pdfFile.getAbsolutePath(), 2);
        assertEquals(2, fileReport.pageCount);

        Uri uri = FileProvider.getUriForFile(context,
            context.getPackageName() + ".fileprovider", pdfFile);
        PdfFileValidator.Report uriReport = PdfFileValidator.validate(context, uri.toString(), 2);
        assertEquals(2, uriReport.pageCount);
    }

    @Test
    public void reportsPageMismatch() throws Exception {
        try {
            PdfFileValidator.validate(context, pdfFile.getAbsolutePath(), 3);
            fail("expected page mismatch");
        } catch (PdfFileValidator.ValidationException error) {
            assertEquals("PDF_PAGE_MISMATCH", error.code);
        }
    }

    @Test
    public void reportsMissingFileAsInvalidInput() throws Exception {
        assertEquals(true, pdfFile.delete());
        try {
            PdfFileValidator.validate(context, pdfFile.getAbsolutePath(), 2);
            fail("expected missing file failure");
        } catch (PdfFileValidator.ValidationException error) {
            assertEquals("PDF_MISSING", error.code);
        }
    }
}
