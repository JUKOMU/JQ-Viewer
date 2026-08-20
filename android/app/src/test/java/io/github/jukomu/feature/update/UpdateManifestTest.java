package io.github.jukomu.feature.update;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpdateManifestTest {

    @Test
    public void acceptsTheStableManifestContract() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(validManifest());

        assertTrue("v1.4.0".equals(manifest.getTag()));
        assertTrue("1.4.0".equals(manifest.getVersionName()));
        assertTrue(manifest.getVersionCode() == 16L);
        assertTrue(manifest.getGithubUrl().startsWith("https://"));
        assertTrue(manifest.sameRelease(UpdateManifest.parse(validManifest())));
    }

    @Test
    public void selectsTheFirstSupportedAbiAndFallsBackToUniversal() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(manifestWithVariants());

        UpdateManifest arm64 = manifest.selectForAbis(
            new String[] { "unsupported", "arm64-v8a", "armeabi-v7a" });
        UpdateManifest x86 = manifest.selectForAbis(new String[] { "x86" });
        UpdateManifest universal = manifest.selectForAbis(new String[] { "riscv64" });

        assertTrue(arm64.getApkName().endsWith("-arm64-v8a.apk"));
        assertTrue(arm64.getSizeBytes() == 1001L);
        assertTrue(x86.getApkName().endsWith("-x86.apk"));
        assertTrue(universal.getApkName().equals("JQ-Viewer-1_4_0-universal.apk"));
        assertTrue(universal.getSizeBytes() == 73400320L);
    }

    @Test
    public void acceptsUnknownAbiVariantsAndRejectsIncompleteOrDuplicateVariants() throws Exception {
        StubJSONObject withUnknownAbi = (StubJSONObject) manifestWithVariants();
        StubJSONArray variants = (StubJSONArray) withUnknownAbi.values.get("variants");
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("abi", "riscv64");
        variants.values.add(new StubJSONObject(unknown));
        UpdateManifest.parse(withUnknownAbi);

        expectInvalid(manifestWithVariants("arm64-v8a", true));

        StubJSONObject incomplete = (StubJSONObject) manifestWithVariants();
        incomplete.values.put("variants", new StubJSONArray(new ArrayList<>()));
        expectInvalid(incomplete);
    }

    @Test
    public void rejectsPrereleaseAndWrongCertificate() throws Exception {
        JSONObject prerelease = manifestWith("tag", "v1.4.0-rc.1");
        expectInvalid(prerelease);

        JSONObject wrongCertificate = manifestWith("signingCertificateSha256", repeat('A', 64));
        expectInvalid(wrongCertificate);
    }

    @Test
    public void rejectsNonHttpsOrInconsistentSources() throws Exception {
        JSONObject invalidUrl = manifestWith("giteeUrl", "http://gitee.example/update.apk");
        expectInvalid(invalidUrl);

        expectInvalid(manifestWith("apkName", "../outside.apk"));

        JSONObject different = manifestWith("sha256", repeat('B', 64));
        UpdateManifest first = UpdateManifest.parse(validManifest());
        UpdateManifest second = UpdateManifest.parse(different);
        assertFalse(first.sameRelease(second));
    }

    @Test
    public void rejectsReleaseUrlsOutsideTheExpectedRepositories() throws Exception {
        expectInvalid(manifestWith("githubUrl",
            "https://example.com/JUKOMU/JQ-Viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk"));
        expectInvalid(manifestWith("giteeUrl",
            "https://gitee.com/other/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk"));
        expectInvalid(manifestWith("giteeUrl",
            "https://gitee.com:8443/jukomu/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk"));
        expectInvalid(manifestWith("giteeUrl",
            "https://user@gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk"));
    }

    @Test
    public void rejectsReleaseUrlsWithMismatchedTagNameOrQuery() throws Exception {
        expectInvalid(manifestWith("githubUrl",
            "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.3.0/JQ-Viewer-1_4_0.apk"));
        expectInvalid(manifestWith("giteeUrl",
            "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/other.apk"));
        expectInvalid(manifestWith("giteeUrl",
            "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk?x=1"));
    }

    @Test
    public void validatesGiteeLatestManifestAssetUrl() throws Exception {
        UpdateManifest.requireGiteeReleaseUrl(
            "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/latest.json",
            "v1.4.0", "latest.json");

        try {
            UpdateManifest.requireGiteeReleaseUrl(
                "https://example.com/jukomu/jq-viewer/releases/download/v1.4.0/latest.json",
                "v1.4.0", "latest.json");
            fail("expected invalid Gitee asset URL");
        } catch (UpdateManifest.UpdateException expected) {
            assertTrue(expected.getMessage().contains("Gitee"));
        }
    }

    @Test
    public void selectsEitherOnlyAvailableManifest() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(validManifest());

        UpdateService.ManifestResolution githubOnly = UpdateService.resolveManifests(
            UpdateService.ManifestAttempt.success(manifest),
            UpdateService.ManifestAttempt.failure("Gitee unavailable"));
        UpdateService.ManifestResolution giteeOnly = UpdateService.resolveManifests(
            UpdateService.ManifestAttempt.failure("GitHub unavailable"),
            UpdateService.ManifestAttempt.success(manifest));

        assertTrue(githubOnly.success);
        assertTrue(githubOnly.manifest == manifest);
        assertTrue(giteeOnly.success);
        assertTrue(giteeOnly.manifest == manifest);
    }

    @Test
    public void selectsConsistentSuccessfulManifests() throws Exception {
        UpdateManifest github = UpdateManifest.parse(validManifest());
        UpdateManifest gitee = UpdateManifest.parse(validManifest());

        UpdateService.ManifestResolution resolution = UpdateService.resolveManifests(
            UpdateService.ManifestAttempt.success(github),
            UpdateService.ManifestAttempt.success(gitee));

        assertTrue(resolution.success);
        assertTrue(resolution.manifest == github);
    }

    @Test
    public void rejectsWhenBothManifestsFail() {
        UpdateService.ManifestResolution resolution = UpdateService.resolveManifests(
            UpdateService.ManifestAttempt.failure("GitHub unavailable"),
            UpdateService.ManifestAttempt.failure("Gitee unavailable"));

        assertFalse(resolution.success);
        assertTrue(resolution.manifest == null);
        assertTrue(resolution.error.contains("GitHub unavailable"));
        assertTrue(resolution.error.contains("Gitee unavailable"));
    }

    @Test
    public void rejectsInconsistentSuccessfulManifests() throws Exception {
        UpdateManifest first = UpdateManifest.parse(validManifest());
        UpdateManifest second = UpdateManifest.parse(manifestWith("sha256", repeat('B', 64)));

        UpdateService.ManifestResolution resolution = UpdateService.resolveManifests(
            UpdateService.ManifestAttempt.success(first),
            UpdateService.ManifestAttempt.success(second));

        assertFalse(resolution.success);
        assertTrue(resolution.manifest == null);
        assertTrue(resolution.error.contains("不一致"));
    }

    private static JSONObject validManifest() throws Exception {
        return manifestWith(null, null);
    }

    private static JSONObject manifestWithVariants() throws Exception {
        return manifestWithVariants(null, false);
    }

    private static JSONObject manifestWithVariants(String overriddenAbi, boolean duplicate)
        throws Exception {
        StubJSONObject manifest = (StubJSONObject) manifestWith(null, null);
        String[] abis = { "arm64-v8a", "armeabi-v7a", "x86_64", "x86" };
        List<JSONObject> variants = new ArrayList<>(abis.length);
        for (int index = 0; index < abis.length; index++) {
            String abi = index == 0 && overriddenAbi != null ? overriddenAbi : abis[index];
            if (duplicate && index == 1) {
                abi = "arm64-v8a";
            }
            String apkName = "JQ-Viewer-1_4_0-" + abi + ".apk";
            Map<String, Object> sources = new HashMap<>();
            sources.put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.0/"
                + apkName);
            sources.put("gitee", "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/"
                + apkName);
            Map<String, Object> values = new HashMap<>();
            values.put("abi", abi);
            values.put("apkName", apkName);
            values.put("sizeBytes", 1001L + index);
            values.put("sha256", repeat((char) ('a' + index), 64));
            values.put("sources", new StubJSONObject(sources));
            variants.add(new StubJSONObject(values));
        }
        manifest.values.put("apkName", "JQ-Viewer-1_4_0-universal.apk");
        manifest.values.put("sources", releaseSources("JQ-Viewer-1_4_0-universal.apk"));
        manifest.values.put("variants", new StubJSONArray(variants));
        return manifest;
    }

    private static JSONObject manifestWith(String override, String value) throws Exception {
        String giteeUrl = "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk";
        String tag = "v1.4.0";
        String sha256 = repeat('a', 64);
        String certificate = "6667B73DA7322E56626D82CA0EFCF03386E0B5560E14DF48C6025142FF4197EA";
        String apkName = "JQ-Viewer-1_4_0.apk";
        String githubUrl = "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk";
        if ("githubUrl".equals(override)) {
            githubUrl = value;
        } else if ("giteeUrl".equals(override)) {
            giteeUrl = value;
        } else if ("tag".equals(override)) {
            tag = value;
        } else if ("sha256".equals(override)) {
            sha256 = value;
        } else if ("signingCertificateSha256".equals(override)) {
            certificate = value;
        } else if ("apkName".equals(override)) {
            apkName = value;
        }
        Map<String, Object> sources = new HashMap<>();
        sources.put("github", githubUrl);
        sources.put("gitee", giteeUrl);
        Map<String, Object> values = new HashMap<>();
        values.put("schemaVersion", 1);
        values.put("tag", tag);
        values.put("versionName", "1.4.0");
        values.put("versionCode", 16L);
        values.put("packageName", "io.github.jukomu");
        values.put("apkName", apkName);
        values.put("sizeBytes", 73400320L);
        values.put("sha256", sha256);
        values.put("signingCertificateSha256", certificate);
        values.put("releaseNotes", "# v1.4.0\n\n## 新增");
        values.put("sources", new StubJSONObject(sources));
        return new StubJSONObject(values);
    }

    private static StubJSONObject releaseSources(String apkName) {
        Map<String, Object> sources = new HashMap<>();
        sources.put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.0/"
            + apkName);
        sources.put("gitee", "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/"
            + apkName);
        return new StubJSONObject(sources);
    }

    private static void expectInvalid(JSONObject manifest) throws Exception {
        try {
            UpdateManifest.parse(manifest);
            fail("expected invalid manifest");
        } catch (UpdateManifest.UpdateException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class StubJSONObject extends JSONObject {
        private final Map<String, Object> values;

        private StubJSONObject(Map<String, Object> values) {
            super();
            this.values = values;
        }

        @Override
        public boolean has(String name) {
            return values.containsKey(name);
        }

        @Override
        public boolean isNull(String name) {
            return !values.containsKey(name) || values.get(name) == null;
        }

        @Override
        public String getString(String name) {
            return (String) values.get(name);
        }

        @Override
        public int getInt(String name) {
            return ((Number) values.get(name)).intValue();
        }

        @Override
        public long getLong(String name) {
            return ((Number) values.get(name)).longValue();
        }

        @Override
        public JSONObject getJSONObject(String name) {
            return (JSONObject) values.get(name);
        }

        @Override
        public JSONArray optJSONArray(String name) {
            return (JSONArray) values.get(name);
        }

        @Override
        public String optString(String name, String fallback) {
            Object value = values.get(name);
            return value == null ? fallback : (String) value;
        }
    }

    private static final class StubJSONArray extends JSONArray {
        private final List<JSONObject> values;

        private StubJSONArray(List<JSONObject> values) {
            super();
            this.values = values;
        }

        @Override
        public int length() {
            return values.size();
        }

        @Override
        public JSONObject optJSONObject(int index) {
            return index >= 0 && index < values.size() ? values.get(index) : null;
        }
    }
}
