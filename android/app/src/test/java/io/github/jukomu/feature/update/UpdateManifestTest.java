package io.github.jukomu.feature.update;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
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

    private static JSONObject manifestWith(String override, String value) throws Exception {
        String giteeUrl = "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk";
        String tag = "v1.4.0";
        String sha256 = repeat('a', 64);
        String certificate = "6667B73DA7322E56626D82CA0EFCF03386E0B5560E14DF48C6025142FF4197EA";
        String apkName = "JQ-Viewer-1_4_0.apk";
        if ("giteeUrl".equals(override)) {
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
        sources.put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.0/JQ-Viewer-1_4_0.apk");
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
        public String optString(String name, String fallback) {
            Object value = values.get(name);
            return value == null ? fallback : (String) value;
        }
    }
}
