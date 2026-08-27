package com.moakiee.thunderbolt.core.crafting.planner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Downloads a pinned, platform-specific CP-SAT runtime into the game cache. */
final class CpSatRuntimeInstaller {
    static final String ORTOOLS_VERSION = "9.15.6755";
    static final String PROTOBUF_VERSION = "4.33.1";
    private static final URI MAVEN_CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90L);
    private static final EmbeddedArtifact ORTOOLS_JAVA = new EmbeddedArtifact(
            "ortools-java-" + ORTOOLS_VERSION + ".jar",
            "483f27408386fd1ab718aa72244beead10756e1be6d25c7ab7fcb83eb1788d9a");
    private static final EmbeddedArtifact PROTOBUF_JAVA = new EmbeddedArtifact(
            "protobuf-java-" + PROTOBUF_VERSION + ".jar",
            "fd5cf3d55bc2c3ddb2a8640c9d4c69daa9a5b326fb6e05bae0e56b3f4f85e0f7");

    private CpSatRuntimeInstaller() {
    }

    static List<Path> install(Path cacheRoot) throws IOException {
        String platform = currentPlatform();
        Artifact nativeRuntime = nativeArtifact(platform);
        Path versionDirectory = cacheRoot.resolve(ORTOOLS_VERSION).resolve(platform);
        Files.createDirectories(versionDirectory);

        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return List.of(
                extractOrReuse(versionDirectory, ORTOOLS_JAVA),
                extractOrReuse(versionDirectory, PROTOBUF_JAVA),
                downloadOrReuse(client, versionDirectory, nativeRuntime));
    }

    static String currentPlatform() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = architecture.equals("aarch64") || architecture.equals("arm64");
        boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64")
                || architecture.equals("x64");
        if ((os.contains("mac") || os.contains("darwin")) && arm64) {
            return "darwin-aarch64";
        }
        if ((os.contains("mac") || os.contains("darwin")) && x64) {
            return "darwin-x86-64";
        }
        if (os.contains("linux") && arm64) {
            return "linux-aarch64";
        }
        if (os.contains("linux") && x64) {
            return "linux-x86-64";
        }
        if (os.contains("win") && x64) {
            return "win32-x86-64";
        }
        throw new IOException("unsupported operating system or architecture: os="
                + System.getProperty("os.name") + ", arch=" + System.getProperty("os.arch"));
    }

    private static Artifact nativeArtifact(String platform) throws IOException {
        String sha256 = switch (platform) {
            case "darwin-aarch64" ->
                    "a881318798220eef89bd51872cbb78677e510a634b32493cd5c544b38e15ee25";
            case "darwin-x86-64" ->
                    "f0b982a1121a62e9d49ff253eea6f149acc30aab1c7bc07b7db7cf320a0668d6";
            case "linux-aarch64" ->
                    "4c61daad85c14cecdab8df5097122e46ccd73a64f4acf41e5933db40eb6456cc";
            case "linux-x86-64" ->
                    "6f5870f5a229a6916407fbf821f1d58a1a95405aa46d79542b59845f7aa30278";
            case "win32-x86-64" ->
                    "47f364a870e0bbc14ed86c1aa95c90eac1d1fa6d13bdbba745b2dfd4d3ebd068";
            default -> throw new IOException("unsupported CP-SAT platform: " + platform);
        };
        return new Artifact(
                "com/google/ortools/ortools-" + platform + "/" + ORTOOLS_VERSION
                        + "/ortools-" + platform + "-" + ORTOOLS_VERSION + ".jar",
                sha256);
    }

    private static Path downloadOrReuse(
            HttpClient client, Path directory, Artifact artifact) throws IOException {
        String fileName = Path.of(artifact.mavenPath()).getFileName().toString();
        Path target = directory.resolve(fileName);
        if (Files.isRegularFile(target) && artifact.sha256().equals(sha256(target))) {
            return target;
        }

        Path temporary = Files.createTempFile(directory, fileName + ".", ".part");
        boolean installed = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(MAVEN_CENTRAL.resolve(artifact.mavenPath()))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Path> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + fileName, interrupted);
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + fileName);
            }
            String actualHash = sha256(temporary);
            if (!artifact.sha256().equals(actualHash)) {
                throw new IOException("SHA-256 mismatch for " + fileName + ": expected "
                        + artifact.sha256() + ", got " + actualHash);
            }
            moveIntoPlace(temporary, target);
            installed = true;
            return target;
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path extractOrReuse(Path directory, EmbeddedArtifact artifact)
            throws IOException {
        Path target = directory.resolve(artifact.fileName());
        if (Files.isRegularFile(target) && artifact.sha256().equals(sha256(target))) {
            return target;
        }

        String resource = "META-INF/thunderbolt/cp-sat/" + artifact.fileName();
        Path temporary = Files.createTempFile(directory, artifact.fileName() + ".", ".part");
        boolean installed = false;
        try (InputStream input = CpSatRuntimeInstaller.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing embedded CP-SAT Java runtime " + resource);
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            String actualHash = sha256(temporary);
            if (!artifact.sha256().equals(actualHash)) {
                throw new IOException("SHA-256 mismatch for embedded " + artifact.fileName()
                        + ": expected " + artifact.sha256() + ", got " + actualHash);
            }
            moveIntoPlace(temporary, target);
            installed = true;
            return target;
        } finally {
            if (!installed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Artifact(String mavenPath, String sha256) {
    }

    private record EmbeddedArtifact(String fileName, String sha256) {
    }
}
