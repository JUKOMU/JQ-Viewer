package io.github.jukomu.desktop.feature.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopUpdateManifestTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void verifiesExactBytesAndSelectsCurrentPackage() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DesktopUpdateConfiguration configuration = configuration(keyPair);
        byte[] manifest = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest());
        byte[] signature = signature(manifest, keyPair);

        DesktopUpdateManifest.VerifiedRelease release = DesktopUpdateManifest.verifyAndParse(
                manifest, signature, configuration, MAPPER);

        assertEquals("JQ-Viewer-1.4.7-linux-x64.tar.gz",
                release.artifact().name());
        assertEquals("portable", release.artifact().packageType());
        assertEquals("tar.gz", release.artifact().packageFormat());

        byte[] changedBytes = (new String(manifest, java.nio.charset.StandardCharsets.UTF_8) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(UpdateException.class, () -> DesktopUpdateManifest.verifyAndParse(
                changedBytes, signature, configuration, MAPPER));
    }

    @Test
    void requiresByteIdenticalMirrors() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DesktopUpdateConfiguration configuration = configuration(keyPair);
        ObjectNode document = manifest();
        byte[] compact = MAPPER.writeValueAsBytes(document);
        byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);

        DesktopUpdateManifest.VerifiedRelease first = DesktopUpdateManifest.verifyAndParse(
                compact, signature(compact, keyPair), configuration, MAPPER);
        DesktopUpdateManifest.VerifiedRelease second = DesktopUpdateManifest.verifyAndParse(
                pretty, signature(pretty, keyPair), configuration, MAPPER);

        assertFalse(DesktopUpdateManifest.sameRelease(first, second));

        byte[] signature = signature(compact, keyPair);
        ObjectNode signatureDocument = (ObjectNode) MAPPER.readTree(signature);
        byte[] reformattedSignature = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(signatureDocument);
        DesktopUpdateManifest.VerifiedRelease reformatted = DesktopUpdateManifest.verifyAndParse(
                compact, reformattedSignature, configuration, MAPPER);
        assertFalse(DesktopUpdateManifest.sameRelease(first, reformatted));
    }

    @Test
    void comparesOnlyStableSemanticVersions() {
        assertEquals(1, DesktopUpdateService.compareVersions("1.5.0", "1.4.9"));
        assertEquals(0, DesktopUpdateService.compareVersions("1.4.6", "1.4.6"));
        assertEquals(-1, DesktopUpdateService.compareVersions("1.4.5", "1.4.6"));
        assertThrows(UpdateException.class,
                () -> DesktopUpdateService.compareVersions("1.4-beta", "1.4.6"));
    }

    private static DesktopUpdateConfiguration configuration(KeyPair keyPair) {
        return new DesktopUpdateConfiguration(
                "1.4.6", "linux", "x64", "portable", "tar.gz",
                "jq-viewer-release-1", keyPair.getPublic().getEncoded(),
                Path.of("/opt/JQ-Viewer/bin/JQ-Viewer"), Path.of("/opt/JQ-Viewer"));
    }

    private static byte[] signature(byte[] manifest, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifest);
        ObjectNode document = MAPPER.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("algorithm", "Ed25519");
        document.put("keyId", "jq-viewer-release-1");
        document.put("manifest", "latest.json");
        document.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
        return MAPPER.writeValueAsBytes(document);
    }

    private static ObjectNode manifest() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("tag", "v1.4.7");
        root.put("versionName", "1.4.7");
        root.put("versionCode", 147);
        root.put("packageName", "io.github.jukomu.jqviewer");
        root.put("apkName", "JQ-Viewer-1_4_7-universal.apk");
        root.put("sizeBytes", 1024);
        root.put("sha256", "a".repeat(64));
        root.put("signingCertificateSha256", "b".repeat(64));
        root.put("releaseNotes", "release");
        sources(root.putObject("sources"), "JQ-Viewer-1_4_7-universal.apk");
        ArrayNode artifacts = root.putObject("desktop").putArray("artifacts");
        artifact(artifacts, "windows", "x64", "installer", "exe", "x64", "arm64");
        artifact(artifacts, "windows", "x64", "portable", "zip", "x64", "arm64");
        for (String architecture : new String[]{"x64", "arm64"}) {
            artifact(artifacts, "linux", architecture, "installer", "deb", architecture);
            artifact(artifacts, "linux", architecture, "installer", "rpm", architecture);
            artifact(artifacts, "linux", architecture, "portable", "tar.gz", architecture);
        }
        return root;
    }

    private static void artifact(
            ArrayNode artifacts,
            String platform,
            String architecture,
            String packageType,
            String packageFormat,
            String... compatibleArchitectures
    ) {
        String suffix = switch (packageFormat) {
            case "exe" -> "installer.exe";
            default -> packageFormat;
        };
        String separator = packageFormat.equals("exe") ? "-" : ".";
        String name = "JQ-Viewer-1.4.7-" + platform + "-" + architecture + separator + suffix;
        ObjectNode artifact = artifacts.addObject();
        artifact.put("name", name);
        artifact.put("platform", platform);
        artifact.put("architecture", architecture);
        ArrayNode compatible = artifact.putArray("compatibleArchitectures");
        for (String value : compatibleArchitectures) compatible.add(value);
        artifact.put("packageType", packageType);
        artifact.put("packageFormat", packageFormat);
        artifact.put("sizeBytes", 2048);
        artifact.put("sha256", "c".repeat(64));
        sources(artifact.putObject("sources"), name);
    }

    private static void sources(ObjectNode sources, String name) {
        sources.put("github", "https://github.com/JUKOMU/JQ-Viewer/releases/download/v1.4.7/" + name);
        sources.put("gitee", "https://gitee.com/jukomu/jq-viewer/releases/download/v1.4.7/" + name);
    }
}
