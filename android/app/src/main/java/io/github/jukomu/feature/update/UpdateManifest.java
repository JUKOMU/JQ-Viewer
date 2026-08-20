package io.github.jukomu.feature.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 应用内更新的远程发布契约。
 *
 * <p>只接受正式版 manifest；预发布 Tag、非 HTTPS 地址和字段不完整的内容均拒绝。
 */
public final class UpdateManifest {

    private static final Pattern RELEASE_TAG_PATTERN =
        Pattern.compile("^v[0-9]+\\.[0-9]+\\.[0-9]+$");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern APK_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+\\.apk$");
    private static final String EXPECTED_PACKAGE_NAME = "io.github.jukomu";
    private static final String GITHUB_HOST = "github.com";
    private static final String GITHUB_REPOSITORY_PATH = "/JUKOMU/JQ-Viewer";
    private static final String GITEE_HOST = "gitee.com";
    private static final String GITEE_REPOSITORY_PATH = "/jukomu/jq-viewer";
    private static final String EXPECTED_CERTIFICATE_SHA256 =
        "6667B73DA7322E56626D82CA0EFCF03386E0B5560E14DF48C6025142FF4197EA";
    private static final Set<String> SUPPORTED_VARIANT_ABIS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("arm64-v8a", "armeabi-v7a", "x86_64", "x86")));

    private final int schemaVersion;
    private final String tag;
    private final String versionName;
    private final long versionCode;
    private final String packageName;
    private final String apkName;
    private final long sizeBytes;
    private final String sha256;
    private final String signingCertificateSha256;
    private final String releaseNotes;
    private final String githubUrl;
    private final String giteeUrl;
    private final List<Variant> variants;

    private UpdateManifest(int schemaVersion, String tag, String versionName, long versionCode,
                           String packageName, String apkName, long sizeBytes, String sha256,
                           String signingCertificateSha256, String releaseNotes,
                           String githubUrl, String giteeUrl, List<Variant> variants) {
        this.schemaVersion = schemaVersion;
        this.tag = tag;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.packageName = packageName;
        this.apkName = apkName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.signingCertificateSha256 = signingCertificateSha256;
        this.releaseNotes = releaseNotes;
        this.githubUrl = githubUrl;
        this.giteeUrl = giteeUrl;
        this.variants = variants;
    }

    /**
     * 解析并校验正式版 manifest。
     *
     * @param json 远程 latest.json 内容
     * @return 校验通过的 manifest
     * @throws UpdateException manifest 不符合发布契约时抛出
     */
    public static UpdateManifest parse(JSONObject json) throws UpdateException {
        if (json == null) {
            throw new UpdateException("更新元数据为空");
        }

        try {
            int schemaVersion = requiredInt(json, "schemaVersion");
            String tag = requiredString(json, "tag");
            String versionName = requiredString(json, "versionName");
            long versionCode = requiredLong(json, "versionCode");
            String packageName = requiredString(json, "packageName");
            String apkName = requiredString(json, "apkName");
            long sizeBytes = requiredLong(json, "sizeBytes");
            String sha256 = requiredString(json, "sha256").toLowerCase(Locale.ROOT);
            String certificate = requiredString(json, "signingCertificateSha256")
                .toUpperCase(Locale.ROOT);
            String releaseNotes = json.optString("releaseNotes", "");
            JSONObject sources = requiredObject(json, "sources");
            String githubUrl = requiredString(sources, "github");
            String giteeUrl = requiredString(sources, "gitee");
            List<Variant> variants = parseVariants(json.optJSONArray("variants"), tag);

            validate(schemaVersion, tag, versionName, versionCode, packageName, apkName,
                sizeBytes, sha256, certificate, githubUrl, giteeUrl);
            return new UpdateManifest(schemaVersion, tag, versionName, versionCode, packageName,
                apkName, sizeBytes, sha256, certificate, releaseNotes, githubUrl, giteeUrl,
                variants);
        } catch (JSONException | NumberFormatException error) {
            throw new UpdateException("更新元数据字段无效", error);
        }
    }

    /**
     * 判断两个 manifest 是否代表完全一致的发布内容。
     */
    public boolean sameRelease(UpdateManifest other) {
        if (other == null) {
            return false;
        }
        return schemaVersion == other.schemaVersion
            && versionCode == other.versionCode
            && sizeBytes == other.sizeBytes
            && Objects.equals(tag, other.tag)
            && Objects.equals(versionName, other.versionName)
            && Objects.equals(packageName, other.packageName)
            && Objects.equals(apkName, other.apkName)
            && Objects.equals(sha256, other.sha256)
            && Objects.equals(signingCertificateSha256, other.signingCertificateSha256)
            && Objects.equals(releaseNotes, other.releaseNotes)
            && Objects.equals(githubUrl, other.githubUrl)
            && Objects.equals(giteeUrl, other.giteeUrl)
            && Objects.equals(variants, other.variants);
    }

    /**
     * 按设备声明的 ABI 优先级选择专用 APK；没有匹配项时保留顶层 universal APK。
     */
    public UpdateManifest selectForAbis(String[] supportedAbis) {
        if (supportedAbis == null || variants.isEmpty()) {
            return this;
        }
        for (String supportedAbi : supportedAbis) {
            for (Variant variant : variants) {
                if (variant.abi.equals(supportedAbi)) {
                    return new UpdateManifest(schemaVersion, tag, versionName, versionCode,
                        packageName, variant.apkName, variant.sizeBytes, variant.sha256,
                        signingCertificateSha256, releaseNotes, variant.githubUrl,
                        variant.giteeUrl, variants);
                }
            }
        }
        return this;
    }

    private static List<Variant> parseVariants(JSONArray values, String tag)
        throws JSONException, UpdateException {
        if (values == null) {
            return Collections.emptyList();
        }
        List<Variant> variants = new ArrayList<>(values.length());
        Set<String> seenAbis = new HashSet<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) {
                throw new UpdateException("更新 APK 架构条目无效");
            }
            String abi = requiredString(value, "abi");
            if (!seenAbis.add(abi)) {
                throw new UpdateException("更新 APK 架构重复");
            }
            if (!SUPPORTED_VARIANT_ABIS.contains(abi)) {
                continue;
            }
            String apkName = requiredString(value, "apkName");
            long sizeBytes = requiredLong(value, "sizeBytes");
            String sha256 = requiredString(value, "sha256").toLowerCase(Locale.ROOT);
            JSONObject sources = requiredObject(value, "sources");
            String githubUrl = requiredString(sources, "github");
            String giteeUrl = requiredString(sources, "gitee");
            validateArtifact(tag, apkName, sizeBytes, sha256, githubUrl, giteeUrl);
            variants.add(new Variant(abi, apkName, sizeBytes, sha256, githubUrl, giteeUrl));
        }
        if (!seenAbis.containsAll(SUPPORTED_VARIANT_ABIS)) {
            throw new UpdateException("更新 APK 架构条目不完整");
        }
        return Collections.unmodifiableList(variants);
    }

    private static void validate(int schemaVersion, String tag, String versionName,
                                 long versionCode, String packageName, String apkName,
                                 long sizeBytes, String sha256, String certificate,
                                 String githubUrl, String giteeUrl) throws UpdateException {
        if (schemaVersion != 1) {
            throw new UpdateException("不支持的更新元数据版本");
        }
        if (!RELEASE_TAG_PATTERN.matcher(tag).matches()
            || !versionName.equals(tag.substring(1))) {
            throw new UpdateException("更新版本不是正式版或版本号不一致");
        }
        if (versionCode <= 0 || !EXPECTED_PACKAGE_NAME.equals(packageName)) {
            throw new UpdateException("更新包名或版本码无效");
        }
        if (!DIGEST_PATTERN.matcher(certificate).matches()) {
            throw new UpdateException("更新摘要格式无效");
        }
        if (!EXPECTED_CERTIFICATE_SHA256.equals(certificate)) {
            throw new UpdateException("更新签名证书不是正式证书");
        }
        validateArtifact(tag, apkName, sizeBytes, sha256, githubUrl, giteeUrl);
    }

    private static void validateArtifact(String tag, String apkName, long sizeBytes,
                                         String sha256, String githubUrl, String giteeUrl)
        throws UpdateException {
        if (!APK_NAME_PATTERN.matcher(apkName).matches() || sizeBytes <= 0) {
            throw new UpdateException("更新 APK 元数据无效");
        }
        if (!DIGEST_PATTERN.matcher(sha256).matches()) {
            throw new UpdateException("更新摘要格式无效");
        }
        requireReleaseUrl(githubUrl, "GitHub", GITHUB_HOST, GITHUB_REPOSITORY_PATH,
            tag, apkName);
        requireGiteeReleaseUrl(giteeUrl, tag, apkName);
    }

    static void requireGiteeReleaseUrl(String value, String tag, String assetName)
        throws UpdateException {
        requireReleaseUrl(value, "Gitee", GITEE_HOST, GITEE_REPOSITORY_PATH,
            tag, assetName);
    }

    private static void requireReleaseUrl(String value, String source, String expectedHost,
                                          String repositoryPath, String tag, String assetName)
        throws UpdateException {
        if (!RELEASE_TAG_PATTERN.matcher(tag).matches()
            || assetName == null || assetName.isEmpty() || assetName.contains("/")) {
            throw new UpdateException(source + " 更新地址与发布信息不一致");
        }
        try {
            URI uri = new URI(value);
            String expectedPath = repositoryPath + "/releases/download/" + tag + "/" + assetName;
            boolean validPort = uri.getPort() == -1 || uri.getPort() == 443;
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || !validPort
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !expectedPath.equals(uri.getRawPath())) {
                throw new UpdateException(source + " 更新地址不属于固定发布仓库");
            }
        } catch (URISyntaxException error) {
            throw new UpdateException(source + " 更新地址无效", error);
        }
    }

    private static String requiredString(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            throw new JSONException("缺少字段: " + key);
        }
        String value = object.getString(key).trim();
        if (value.isEmpty()) {
            throw new JSONException("字段为空: " + key);
        }
        return value;
    }

    private static int requiredInt(JSONObject object, String key) throws JSONException {
        return object.getInt(key);
    }

    private static long requiredLong(JSONObject object, String key) throws JSONException {
        return object.getLong(key);
    }

    private static JSONObject requiredObject(JSONObject object, String key) throws JSONException {
        return object.getJSONObject(key);
    }

    public String getTag() {
        return tag;
    }

    public String getVersionName() {
        return versionName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getApkName() {
        return apkName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getSigningCertificateSha256() {
        return signingCertificateSha256;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public String getGithubUrl() {
        return githubUrl;
    }

    public String getGiteeUrl() {
        return giteeUrl;
    }

    private static final class Variant {
        private final String abi;
        private final String apkName;
        private final long sizeBytes;
        private final String sha256;
        private final String githubUrl;
        private final String giteeUrl;

        private Variant(String abi, String apkName, long sizeBytes, String sha256,
                        String githubUrl, String giteeUrl) {
            this.abi = abi;
            this.apkName = apkName;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.githubUrl = githubUrl;
            this.giteeUrl = giteeUrl;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Variant)) {
                return false;
            }
            Variant variant = (Variant) other;
            return sizeBytes == variant.sizeBytes
                && Objects.equals(abi, variant.abi)
                && Objects.equals(apkName, variant.apkName)
                && Objects.equals(sha256, variant.sha256)
                && Objects.equals(githubUrl, variant.githubUrl)
                && Objects.equals(giteeUrl, variant.giteeUrl);
        }

        @Override
        public int hashCode() {
            return Objects.hash(abi, apkName, sizeBytes, sha256, githubUrl, giteeUrl);
        }
    }

    /**
     * 更新功能内部使用的业务异常。
     */
    public static final class UpdateException extends Exception {
        public UpdateException(String message) {
            super(message);
        }

        public UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
