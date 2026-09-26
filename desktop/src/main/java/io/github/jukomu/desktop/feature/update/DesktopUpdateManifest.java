package io.github.jukomu.desktop.feature.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 验证共享 latest.json 签名并选择当前 Desktop 包。
 */
public final class DesktopUpdateManifest {
    private static final Pattern TAG_PATTERN = Pattern.compile("^v([0-9]+\\.[0-9]+\\.[0-9]+)$");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern ASSET_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private DesktopUpdateManifest() {
    }

    public static VerifiedRelease verifyAndParse(
        byte[] manifestBytes,
        byte[] signatureBytes,
        DesktopUpdateConfiguration configuration,
        ObjectMapper mapper
    ) {
        Objects.requireNonNull(mapper, "mapper");
        configuration.requireConfigured();
        verifySignature(manifestBytes, signatureBytes, configuration, mapper);

        try {
            JsonNode root = mapper.readTree(manifestBytes);
            if (requiredInt(root, "schemaVersion") != 1) {
                throw new UpdateException("不支持的更新元数据版本");
            }
            String tag = requiredText(root, "tag");
            var tagMatcher = TAG_PATTERN.matcher(tag);
            if (!tagMatcher.matches()) throw new UpdateException("更新版本不是正式版");
            String versionName = requiredText(root, "versionName");
            if (!versionName.equals(tagMatcher.group(1))) {
                throw new UpdateException("更新版本与标签不一致");
            }

            JsonNode artifacts = requiredNode(requiredNode(root, "desktop"), "artifacts");
            if (!artifacts.isArray() || artifacts.size() != 8) {
                throw new UpdateException("Desktop 更新包矩阵不完整");
            }

            List<Artifact> selected = new ArrayList<>();
            for (JsonNode value : artifacts) {
                Artifact artifact = parseArtifact(value, tag);
                if (artifact.matches(configuration)) selected.add(artifact);
            }
            if (selected.size() != 1) {
                throw new UpdateException("无法唯一选择当前 Desktop 更新包");
            }

            Artifact artifact = selected.getFirst();
            ManifestResponse response = new ManifestResponse(
                tag,
                versionName,
                requiredLong(root, "versionCode"),
                requiredText(root, "packageName"),
                requiredText(root, "apkName"),
                requiredLong(root, "sizeBytes"),
                requiredText(root, "sha256"),
                requiredText(root, "signingCertificateSha256"),
                root.path("releaseNotes").asText(""),
                parseSources(requiredNode(root, "sources"), tag, requiredText(root, "apkName")),
                artifact
            );
            return new VerifiedRelease(manifestBytes.clone(), signatureBytes.clone(), response, artifact);
        } catch (UpdateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UpdateException("更新元数据字段无效", exception);
        }
    }

    public static boolean sameRelease(VerifiedRelease first, VerifiedRelease second) {
        return first != null && second != null
            && Arrays.equals(first.manifestBytes(), second.manifestBytes())
            && Arrays.equals(first.signatureBytes(), second.signatureBytes());
    }

    private static void verifySignature(
        byte[] manifestBytes,
        byte[] signatureBytes,
        DesktopUpdateConfiguration configuration,
        ObjectMapper mapper
    ) {
        try {
            JsonNode signatureDocument = mapper.readTree(signatureBytes);
            if (requiredInt(signatureDocument, "schemaVersion") != 1
                || !"Ed25519".equals(requiredText(signatureDocument, "algorithm"))
                || !configuration.keyId().equals(requiredText(signatureDocument, "keyId"))
                || !"latest.json".equals(requiredText(signatureDocument, "manifest"))) {
                throw new UpdateException("发布签名元数据无效");
            }

            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(configuration.publicKeySpki()));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(manifestBytes);
            byte[] signature = Base64.getDecoder().decode(
                requiredText(signatureDocument, "signature"));
            if (!verifier.verify(signature)) {
                throw new UpdateException("发布清单签名校验失败");
            }
        } catch (UpdateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UpdateException("发布签名无效", exception);
        }
    }

    private static Artifact parseArtifact(JsonNode node, String tag) {
        String name = requiredText(node, "name");
        String platform = requiredText(node, "platform").toLowerCase(Locale.ROOT);
        String architecture = requiredText(node, "architecture").toLowerCase(Locale.ROOT);
        String packageType = requiredText(node, "packageType").toLowerCase(Locale.ROOT);
        String packageFormat = requiredText(node, "packageFormat").toLowerCase(Locale.ROOT);
        long sizeBytes = requiredLong(node, "sizeBytes");
        String sha256 = requiredText(node, "sha256").toLowerCase(Locale.ROOT);
        if (!ASSET_NAME_PATTERN.matcher(name).matches() || sizeBytes <= 0
            || !DIGEST_PATTERN.matcher(sha256).matches()) {
            throw new UpdateException("Desktop 更新包元数据无效");
        }

        JsonNode compatible = requiredNode(node, "compatibleArchitectures");
        if (!compatible.isArray() || compatible.isEmpty()) {
            throw new UpdateException("Desktop 更新包兼容架构无效");
        }
        List<String> architectures = new ArrayList<>();
        compatible.forEach(value -> architectures.add(value.asText("").toLowerCase(Locale.ROOT)));
        Sources sources = parseSources(requiredNode(node, "sources"), tag, name);
        return new Artifact(name, platform, architecture, List.copyOf(architectures), packageType,
            packageFormat, sizeBytes, sha256, sources);
    }

    private static Sources parseSources(JsonNode node, String tag, String assetName) {
        String github = requiredText(node, "github");
        String gitee = requiredText(node, "gitee");
        requireReleaseUrl(github, "github.com", "/JUKOMU/JQ-Viewer", tag, assetName);
        requireReleaseUrl(gitee, "gitee.com", "/jukomu/jq-viewer", tag, assetName);
        return new Sources(github, gitee);
    }

    static void requireReleaseUrl(
        String value,
        String expectedHost,
        String repositoryPath,
        String tag,
        String assetName
    ) {
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
                throw new UpdateException("更新地址不属于固定发布仓库");
            }
        } catch (URISyntaxException exception) {
            throw new UpdateException("更新地址无效", exception);
        }
    }

    private static JsonNode requiredNode(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) throw new UpdateException("缺少字段: " + field);
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = requiredNode(node, field).asText("").trim();
        if (value.isEmpty()) throw new UpdateException("字段为空: " + field);
        return value;
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new UpdateException("字段不是整数: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new UpdateException("字段不是整数: " + field);
        }
        return value.longValue();
    }

    public record Sources(String github, String gitee) {
    }

    public record Artifact(
        String name,
        String platform,
        String architecture,
        List<String> compatibleArchitectures,
        String packageType,
        String packageFormat,
        long sizeBytes,
        String sha256,
        Sources sources
    ) {
        boolean matches(DesktopUpdateConfiguration configuration) {
            return platform.equals(configuration.platform())
                && packageType.equals(configuration.packageType())
                && packageFormat.equals(configuration.packageFormat())
                && compatibleArchitectures.contains(configuration.architecture());
        }
    }

    public record ManifestResponse(
        String tag,
        String versionName,
        long versionCode,
        String packageName,
        String apkName,
        long sizeBytes,
        String sha256,
        String signingCertificateSha256,
        String releaseNotes,
        Sources sources,
        Artifact desktopArtifact
    ) {
    }

    public record VerifiedRelease(
        byte[] manifestBytes,
        byte[] signatureBytes,
        ManifestResponse response,
        Artifact artifact
    ) {
        public VerifiedRelease {
            manifestBytes = manifestBytes.clone();
            signatureBytes = signatureBytes.clone();
        }

        @Override
        public byte[] manifestBytes() {
            return manifestBytes.clone();
        }

        @Override
        public byte[] signatureBytes() {
            return signatureBytes.clone();
        }
    }
}
