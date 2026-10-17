package io.github.jukomu.feature.localfile.management;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ZipFileValidatorInstrumentedTest {
    @Test
    public void verifiesArchiveAndDistinguishesMismatchAndMissing() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = new File(context.getCacheDir(), "zip-validator-test.zip");
        try {
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
                output.putNextEntry(new ZipEntry("001_Chapter/0001.jpg"));
                output.write(new byte[]{1, 2, 3});
                output.closeEntry();
            }
            String ref = LocalFileRef.createPathFileRef(archive.getCanonicalPath());
            assertEquals(1, ZipFileValidator.validate(context, ref, 1).pageCount);
            try {
                ZipFileValidator.validate(context, ref, 2);
                fail("expected page mismatch");
            } catch (ZipFileValidator.ValidationException error) {
                assertEquals("ZIP_PAGE_MISMATCH", error.code);
            }
            assertEquals(true, archive.delete());
            try {
                ZipFileValidator.validate(context, ref, 1);
                fail("expected missing file");
            } catch (ZipFileValidator.ValidationException error) {
                assertEquals("ZIP_MISSING", error.code);
            }
        } finally {
            archive.delete();
        }
    }
}
