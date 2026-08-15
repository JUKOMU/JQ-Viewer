package io.github.jukomu.feature.pdf.export;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PdfWriteStrategyExperimentInstrumentedTest {

    private static final String TAG = "PdfWriteStrategyPhase2";
    private static final String RUN_MANUAL_ARGUMENT = "runManualPdfExperiment";

    @Test
    public void measuresWriterStrategiesOnRealImages() throws Exception {
        Assume.assumeTrue(
            "Manual PDF experiment; pass runManualPdfExperiment=true and sourceRoot",
            Boolean.parseBoolean(argument(RUN_MANUAL_ARGUMENT, "false"))
        );
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File sourceRoot = resolveSourceRoot();
        boolean keepOutputs = Boolean.parseBoolean(argument("keepOutputs", "false"));
        int maxPages = parseIntArgument("maxPages", 0);

        long inspectStartedAt = SystemClock.elapsedRealtimeNanos();
        List<PdfBoxExportWriter.ExportImageDescriptor> images = collectImageDescriptors(sourceRoot);
        if (maxPages > 0 && images.size() > maxPages) {
            images = new ArrayList<>(images.subList(0, maxPages));
        }
        long inspectNanos = SystemClock.elapsedRealtimeNanos() - inspectStartedAt;
        if (images.isEmpty()) {
            throw new IOException("No phase 2 experiment images found: " + sourceRoot);
        }

        File outputDir = new File(context.getExternalFilesDir(null), "pdf-phase2");
        deleteRecursively(outputDir);
        if (!outputDir.mkdirs()) {
            throw new IOException("Unable to create phase 2 output directory: " + outputDir);
        }
        File json = new File(outputDir, "phase2-strategy-result.json");

        List<Scenario> scenarios = buildScenarios();
        List<ScenarioResult> results = new ArrayList<>(scenarios.size());
        PdfBoxExportWriter writer = new PdfBoxExportWriter(context);
        Throwable firstFailure = null;
        for (Scenario scenario : scenarios) {
            File output = new File(outputDir, scenario.name + ".pdf");
            try {
                Log.i(TAG, "phase2 scenario start: " + scenario.name
                    + ", pages=" + images.size());
                PdfBoxExportWriter.WriteReport report = writer.writeVolume(
                    images,
                    output,
                    true,
                    1F,
                    scenario.strategy,
                    new NoOpProgressListener()
                );
                assertPdf(output, images);
                boolean outputRetained = keepOutputs;
                if (!keepOutputs && output.exists() && !output.delete()) {
                    throw new IOException("Unable to delete phase 2 scenario PDF: " + output);
                }
                ScenarioResult result = ScenarioResult.success(scenario, output, report, outputRetained);
                results.add(result);
                Log.i(TAG, result.toLogMessage());
            } catch (Throwable t) {
                ScenarioResult result = ScenarioResult.failure(scenario, output, t);
                results.add(result);
                Log.e(TAG, result.toLogMessage(), t);
                if (firstFailure == null) {
                    firstFailure = t;
                }
            } finally {
                writeJsonSafely(json, sourceRoot, images, inspectNanos, keepOutputs, results);
            }
        }

        writeJsonSafely(json, sourceRoot, images, inspectNanos, keepOutputs, results);
        Log.i(TAG, "phase2 result json=" + json.getAbsolutePath());
        if (firstFailure != null) {
            throw new AssertionError("At least one phase 2 strategy failed", firstFailure);
        }
        assertEquals(scenarios.size(), results.size());
    }

    private static List<Scenario> buildScenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("direct", PdfBoxExportWriter.WriteStrategy.direct()));
        scenarios.add(new Scenario(
            "chunk100-optimize",
            PdfBoxExportWriter.WriteStrategy.chunked(
                100,
                PdfBoxExportWriter.MergeMode.OPTIMIZE_RESOURCES
            )
        ));
        scenarios.add(new Scenario(
            "chunk100-legacy",
            PdfBoxExportWriter.WriteStrategy.chunked(100, PdfBoxExportWriter.MergeMode.LEGACY)
        ));
        scenarios.add(new Scenario(
            "chunk250-optimize",
            PdfBoxExportWriter.WriteStrategy.chunked(
                250,
                PdfBoxExportWriter.MergeMode.OPTIMIZE_RESOURCES
            )
        ));
        scenarios.add(new Scenario(
            "chunk250-legacy",
            PdfBoxExportWriter.WriteStrategy.chunked(250, PdfBoxExportWriter.MergeMode.LEGACY)
        ));
        scenarios.add(new Scenario(
            "chunk500-optimize",
            PdfBoxExportWriter.WriteStrategy.chunked(
                500,
                PdfBoxExportWriter.MergeMode.OPTIMIZE_RESOURCES
            )
        ));
        scenarios.add(new Scenario(
            "chunk500-legacy",
            PdfBoxExportWriter.WriteStrategy.chunked(500, PdfBoxExportWriter.MergeMode.LEGACY)
        ));
        scenarios.add(new Scenario(
            "chunk1000-optimize",
            PdfBoxExportWriter.WriteStrategy.chunked(
                1000,
                PdfBoxExportWriter.MergeMode.OPTIMIZE_RESOURCES
            )
        ));
        scenarios.add(new Scenario(
            "chunk1000-legacy",
            PdfBoxExportWriter.WriteStrategy.chunked(1000, PdfBoxExportWriter.MergeMode.LEGACY)
        ));
        return scenarios;
    }

    private static File resolveSourceRoot() {
        String sourceRootArg = argument("sourceRoot", "");
        if (sourceRootArg.isEmpty()) {
            throw new IllegalArgumentException("sourceRoot is required for the manual PDF experiment");
        }
        return new File(sourceRootArg.trim());
    }

    private static void writeJsonSafely(File json, File sourceRoot,
                                        List<PdfBoxExportWriter.ExportImageDescriptor> images,
                                        long inspectNanos, boolean keepOutputs,
                                        List<ScenarioResult> results) {
        try {
            writeJson(json, sourceRoot, images, inspectNanos, keepOutputs, results);
        } catch (IOException e) {
            Log.w(TAG, "Unable to write phase 2 result JSON", e);
        }
    }

    private static String argument(String name, String defaultValue) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    private static int parseIntArgument(String name, int defaultValue) {
        String value = argument(name, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static List<PdfBoxExportWriter.ExportImageDescriptor> collectImageDescriptors(File root)
        throws IOException {
        if (!root.isDirectory()) {
            throw new IOException("Phase 2 source root is not a directory: " + root);
        }
        List<File> files = new ArrayList<>();
        collectImages(root, files);
        Collections.sort(files, (left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        List<PdfBoxExportWriter.ExportImageDescriptor> descriptors = new ArrayList<>(files.size());
        for (File file : files) {
            descriptors.add(PdfBoxExportWriter.inspectImage(file));
        }
        return descriptors;
    }

    private static void collectImages(File directory, List<File> images) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectImages(child, images);
            } else if (isImageFile(child)) {
                images.add(child);
            }
        }
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".png")
            || name.endsWith(".webp")
            || name.endsWith(".bmp")
            || name.endsWith(".gif");
    }

    private static void assertPdf(File file,
                                  List<PdfBoxExportWriter.ExportImageDescriptor> images)
        throws IOException {
        assertTrue(file.length() > 0L);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        ); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            assertEquals(images.size(), renderer.getPageCount());
            assertPageSize(renderer, 0, images.get(0));
            if (images.size() > 1) {
                assertPageSize(renderer, images.size() - 1, images.get(images.size() - 1));
            }
        }
    }

    private static void assertPageSize(PdfRenderer renderer, int index,
                                       PdfBoxExportWriter.ExportImageDescriptor image) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            assertEquals(image.width, page.getWidth());
            assertEquals(image.height, page.getHeight());
        }
    }

    private static void writeJson(File json, File sourceRoot,
                                  List<PdfBoxExportWriter.ExportImageDescriptor> images,
                                  long inspectNanos, boolean keepOutputs,
                                  List<ScenarioResult> results) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(json),
            StandardCharsets.UTF_8
        )) {
            writer.write("{\n");
            writer.write("  \"stage\": \"2\",\n");
            writer.write("  \"sourceRoot\": " + jsonString(sourceRoot.getAbsolutePath()) + ",\n");
            writer.write("  \"totalPages\": " + images.size() + ",\n");
            writer.write("  \"inputBytes\": " + sumInputBytes(images) + ",\n");
            writer.write("  \"pixelCount\": " + sumPixels(images) + ",\n");
            writer.write("  \"descriptorInspectMs\": " + millis(inspectNanos) + ",\n");
            writer.write("  \"keepOutputs\": " + keepOutputs + ",\n");
            writer.write("  \"results\": [\n");
            for (int index = 0; index < results.size(); index++) {
                writer.write(results.get(index).toJson());
                writer.write(index + 1 == results.size() ? "\n" : ",\n");
            }
            writer.write("  ]\n");
            writer.write("}\n");
        }
    }

    private static void deleteRecursively(File target) throws IOException {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IOException("Unable to delete phase 2 file: " + target);
        }
    }

    private static long sumInputBytes(List<PdfBoxExportWriter.ExportImageDescriptor> images) {
        long total = 0L;
        for (PdfBoxExportWriter.ExportImageDescriptor image : images) {
            total = saturatingAdd(total, image.fileBytes);
        }
        return total;
    }

    private static long sumPixels(List<PdfBoxExportWriter.ExportImageDescriptor> images) {
        long total = 0L;
        for (PdfBoxExportWriter.ExportImageDescriptor image : images) {
            total = saturatingAdd(total, saturatingMultiply(image.width, image.height));
        }
        return total;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(int left, int right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        long value = (long) left * (long) right;
        return value < 0L ? Long.MAX_VALUE : value;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000D);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final class NoOpProgressListener implements PdfBoxExportWriter.ProgressListener {
        @Override
        public void onPageWritten(int currentPage) {
            // No-op for phase 2 strategy measurement.
        }

        @Override
        public void onFinalizing() {
            // No-op for phase 2 strategy measurement.
        }
    }

    private static final class Scenario {
        final String name;
        final PdfBoxExportWriter.WriteStrategy strategy;

        Scenario(String name, PdfBoxExportWriter.WriteStrategy strategy) {
            this.name = name;
            this.strategy = strategy;
        }
    }

    private static final class ScenarioResult {
        final Scenario scenario;
        final File output;
        final PdfBoxExportWriter.WriteReport report;
        final boolean completed;
        final boolean outputRetained;
        final String errorClass;
        final String errorMessage;

        private ScenarioResult(Scenario scenario, File output,
                               PdfBoxExportWriter.WriteReport report,
                               boolean completed, boolean outputRetained,
                               Throwable error) {
            this.scenario = scenario;
            this.output = output;
            this.report = report;
            this.completed = completed;
            this.outputRetained = outputRetained;
            this.errorClass = error == null ? "" : error.getClass().getName();
            String message = error == null ? "" : error.getMessage();
            this.errorMessage = message == null ? "" : message;
        }

        static ScenarioResult success(Scenario scenario, File output,
                                      PdfBoxExportWriter.WriteReport report,
                                      boolean outputRetained) {
            return new ScenarioResult(scenario, output, report, true, outputRetained, null);
        }

        static ScenarioResult failure(Scenario scenario, File output, Throwable error) {
            return new ScenarioResult(scenario, output, null, false, output.exists(), error);
        }

        String toLogMessage() {
            if (!completed) {
                return "phase2 scenario failed: name=" + scenario.name
                    + ", errorClass=" + errorClass
                    + ", errorMessage=" + errorMessage;
            }
            return "phase2 scenario result: name=" + scenario.name
                + ", completed=" + completed
                + ", outputRetained=" + outputRetained
                + ", totalMs=" + millis(report.totalNanos)
                + ", directWriteMs=" + millis(report.directWriteNanos)
                + ", createImageMs=" + millis(report.createImageNanos)
                + ", saveMs=" + millis(report.saveNanos)
                + ", chunkMs=" + millis(report.chunkNanos)
                + ", mergeMs=" + millis(report.mergeNanos)
                + ", chunkCount=" + report.chunkCount
                + ", chunkBytes=" + report.chunkBytes
                + ", workDirPeakBytes=" + report.workDirPeakBytes
                + ", tempBytes=" + report.tempBytes
                + ", finalBytes=" + report.finalBytes;
        }

        String toJson() {
            StringBuilder builder = new StringBuilder();
            builder.append("    {\n");
            builder.append("      \"name\": ").append(jsonString(scenario.name)).append(",\n");
            builder.append("      \"completed\": ").append(completed).append(",\n");
            builder.append("      \"outputPdf\": ")
                .append(jsonString(output.getAbsolutePath())).append(",\n");
            builder.append("      \"outputRetained\": ").append(outputRetained).append(",\n");
            if (completed) {
                appendReportJson(builder);
            } else {
                builder.append("      \"errorClass\": ").append(jsonString(errorClass)).append(",\n");
                builder.append("      \"errorMessage\": ").append(jsonString(errorMessage)).append("\n");
            }
            builder.append("    }");
            return builder.toString();
        }

        private void appendReportJson(StringBuilder builder) {
            builder.append("      \"strategyDirect\": ").append(report.direct).append(",\n");
            builder.append("      \"chunkPages\": ").append(report.chunkPages).append(",\n");
            builder.append("      \"mergeMode\": ")
                .append(jsonString(report.mergeMode.name())).append(",\n");
            builder.append("      \"totalPages\": ").append(report.totalPages).append(",\n");
            builder.append("      \"pagesWritten\": ").append(report.pagesWritten).append(",\n");
            builder.append("      \"chunkCount\": ").append(report.chunkCount).append(",\n");
            builder.append("      \"unsupportedOriginalFallbackCount\": ")
                .append(report.unsupportedOriginalFallbackCount).append(",\n");
            builder.append("      \"originalDirectJpegCount\": ")
                .append(report.originalDirectJpegCount).append(",\n");
            builder.append("      \"originalTranscodedJpegCount\": ")
                .append(report.originalTranscodedJpegCount).append(",\n");
            builder.append("      \"originalTranscodedJpegBytes\": ")
                .append(report.originalTranscodedJpegBytes).append(",\n");
            builder.append("      \"alphaFlattenCount\": ").append(report.alphaFlattenCount).append(",\n");
            builder.append("      \"totalMs\": ").append(millis(report.totalNanos)).append(",\n");
            builder.append("      \"directWriteMs\": ").append(millis(report.directWriteNanos)).append(",\n");
            builder.append("      \"materializeMs\": ").append(millis(report.materializeNanos)).append(",\n");
            builder.append("      \"decodeMs\": ").append(millis(report.decodeNanos)).append(",\n");
            builder.append("      \"alphaFlattenMs\": ")
                .append(millis(report.alphaFlattenNanos)).append(",\n");
            builder.append("      \"originalJpegEncodeMs\": ")
                .append(millis(report.originalJpegEncodeNanos)).append(",\n");
            builder.append("      \"createImageMs\": ").append(millis(report.createImageNanos)).append(",\n");
            builder.append("      \"boundsDecodeMs\": ")
                .append(millis(report.boundsDecodeNanos)).append(",\n");
            builder.append("      \"addPageMs\": ").append(millis(report.addPageNanos)).append(",\n");
            builder.append("      \"saveMs\": ").append(millis(report.saveNanos)).append(",\n");
            builder.append("      \"chunkMs\": ").append(millis(report.chunkNanos)).append(",\n");
            builder.append("      \"mergeMs\": ").append(millis(report.mergeNanos)).append(",\n");
            builder.append("      \"fsyncMs\": ").append(millis(report.fsyncNanos)).append(",\n");
            builder.append("      \"replaceMs\": ").append(millis(report.replaceNanos)).append(",\n");
            builder.append("      \"chunkBytes\": ").append(report.chunkBytes).append(",\n");
            builder.append("      \"workDirPeakBytes\": ").append(report.workDirPeakBytes).append(",\n");
            builder.append("      \"tempBytes\": ").append(report.tempBytes).append(",\n");
            builder.append("      \"finalBytes\": ").append(report.finalBytes).append(",\n");
            builder.append("      \"javaHeapStartBytes\": ")
                .append(report.javaHeapStartBytes).append(",\n");
            builder.append("      \"javaHeapEndBytes\": ").append(report.javaHeapEndBytes).append(",\n");
            builder.append("      \"nativePssStartBytes\": ")
                .append(report.nativePssStartBytes).append(",\n");
            builder.append("      \"nativePssEndBytes\": ").append(report.nativePssEndBytes).append("\n");
        }
    }
}
