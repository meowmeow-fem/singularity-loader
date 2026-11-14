package net.singularitymc.loader.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.singularitymc.loader.LoaderImpl;
import net.singularitymc.loader.launch.LaunchException;
import net.singularitymc.loader.util.Log;

/**
 * Downloads Mojang's official server jar (the "bundler") as {@code .Singularity/VanillaServerJars/Vanilla-Server-<version>.jar}.
 *
 * <p>The jar is looked up through Mojang's public version manifest, so the exact download URL and its
 * SHA-1 always come from Mojang. A file that is already in the cache and still matches its recorded
 * hash is reused without touching the network.
 */
public final class ServerDownloader {
    /** Mojang's version manifest. Override with {@code -Dsingularity.manifestUrl=...} (mirrors, tests). */
    public static final String DEFAULT_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** The Minecraft version this loader release targets. */
    public static final String DEFAULT_VERSION = "26.3";

    private static final Log LOG = Log.get("Singularity");
    private static final Pattern OBJECT_URL = Pattern.compile("/v1/objects/([0-9a-fA-F]{40})/[^/]+$");

    private ServerDownloader() {}

    /** What to download: where from, how big and which SHA-1 to expect (either may be unknown). */
    record Download(String versionId, URI url, String sha1, long size) {}

    /**
     * Returns the path of a verified server jar, downloading it first if it is not cached.
     *
     * @param serverDir  {@code .singularity/server}
     * @param versionId  Minecraft version (for example {@code 26.3}) or {@code latest}; ignored when {@code directUrl} is set
     * @param directUrl  download this URL instead of consulting the manifest (may be {@code null})
     * @param offline    never use the network
     */
    public static Path ensureServerJar(Path serverDir, String versionId, URI directUrl, boolean offline) throws LaunchException {
        String manifestUrl = System.getProperty("singularity.manifestUrl", DEFAULT_MANIFEST_URL);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

        String dirName;
        if (directUrl != null) {
            String sha = sha1FromObjectUrl(directUrl);
            dirName = "url-" + (sha != null ? sha.substring(0, 12) : Integer.toHexString(directUrl.toString().hashCode()));
        } else if (versionId.equals("latest")) {
            if (offline) {
                throw new LaunchException("Offline mode needs a concrete game version, not 'latest'.");
            }
            dirName = null; // resolved below
        } else {
            dirName = sanitize(versionId);
        }

        Download download = null;
        if (dirName == null) {
            download = resolveFromManifest(client, manifestUrl, versionId);
            dirName = sanitize(download.versionId());
        }
        Path dir = serverDir;
        Path jar = dir.resolve(jarName(dirName));
        Path sidecar = dir.resolve(jarName(dirName) + ".sha1");

        if (isCachedAndValid(jar, sidecar)) {
            LOG.debug("Using cached server jar {}", jar);
            return jar;
        }
        if (offline) {
            throw new LaunchException("Offline mode is on and no verified server jar is cached in " + dir + ".\n"
                    + "Run once without " + "--singularity-offline" + " (internet needed), or use --singularity-server-jar=<path>.");
        }

        if (download == null) {
            download = directUrl != null
                    ? new Download(dirName, directUrl, sha1FromObjectUrl(directUrl), -1)
                    : resolveFromManifest(client, manifestUrl, versionId);
        }
        if (download.sha1() == null) {
            LOG.warn("No SHA-1 is known for {}; the download cannot be verified.", download.url());
        }
        String actualSha1 = fetch(client, download, dir, jar);
        try {
            Files.writeString(sidecar, actualSha1, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LaunchException("Could not write " + sidecar + ": " + e.getMessage(), e);
        }
        return jar;
    }

    /** File name of the downloaded jar for a game version, e.g. {@code Vanilla-Server-26.3.jar}. */
    public static String jarName(String version) {
        return "Vanilla-Server-" + sanitize(version) + ".jar";
    }

    // ---- manifest handling (pure, unit tested) -------------------------------------------------

    /** Finds the URL of the version JSON for {@code versionId} in the manifest ({@code latest} = latest release). */
    static String findVersionUrl(JsonObject manifest, String versionId) throws LaunchException {
        String wanted = versionId;
        if (wanted.equals("latest")) {
            JsonObject latest = manifest.has("latest") && manifest.get("latest").isJsonObject() ? manifest.getAsJsonObject("latest") : null;
            if (latest == null || !latest.has("release")) {
                throw new LaunchException("Mojang's version manifest has no 'latest.release' entry.");
            }
            wanted = latest.get("release").getAsString();
        }
        JsonArray versions = manifest.has("versions") && manifest.get("versions").isJsonArray() ? manifest.getAsJsonArray("versions") : new JsonArray();
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (v.has("id") && v.get("id").getAsString().equals(wanted) && v.has("url")) {
                return v.get("url").getAsString();
            }
        }
        throw new LaunchException("Minecraft version '" + wanted + "' is not in Mojang's version manifest. Check --singularity-game-version.");
    }

    /** Reads {@code downloads.server} from a version JSON. */
    static Download parseServerDownload(JsonObject versionJson) throws LaunchException {
        String id = versionJson.has("id") ? versionJson.get("id").getAsString() : "unknown";
        JsonObject downloads = versionJson.has("downloads") && versionJson.get("downloads").isJsonObject() ? versionJson.getAsJsonObject("downloads") : null;
        JsonObject server = downloads != null && downloads.has("server") && downloads.get("server").isJsonObject() ? downloads.getAsJsonObject("server") : null;
        if (server == null || !server.has("url")) {
            throw new LaunchException("Minecraft " + id + " has no dedicated server download in Mojang's data.");
        }
        String sha1 = server.has("sha1") ? server.get("sha1").getAsString().toLowerCase(Locale.ROOT) : null;
        long size = server.has("size") ? server.get("size").getAsLong() : -1;
        return new Download(id, URI.create(server.get("url").getAsString()), sha1, size);
    }

    /** Mojang's object URLs contain the SHA-1 of the file ({@code /v1/objects/<sha1>/server.jar}); returns it, or {@code null}. */
    static String sha1FromObjectUrl(URI url) {
        Matcher m = OBJECT_URL.matcher(url.getPath() == null ? "" : url.getPath());
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static Download resolveFromManifest(HttpClient client, String manifestUrl, String versionId) throws LaunchException {
        LOG.info("Looking up Minecraft {} in Mojang's version manifest", versionId);
        JsonObject manifest = getJson(client, manifestUrl);
        String versionJsonUrl = findVersionUrl(manifest, versionId);
        return parseServerDownload(getJson(client, versionJsonUrl));
    }

    // ---- network and files -----------------------------------------------------------------------

    private static JsonObject getJson(HttpClient client, String url) throws LaunchException {
        try {
            HttpResponse<String> response = client.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new LaunchException("Downloading " + url + " failed: HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException e) {
            throw networkError(url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("Interrupted while downloading " + url, e);
        } catch (RuntimeException e) {
            throw new LaunchException("Mojang's response from " + url + " could not be understood: " + e.getMessage(), e);
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "SingularityLoader/" + LoaderImpl.readVersion()).GET().build();
    }

    private static LaunchException networkError(String url, IOException e) {
        return new LaunchException("Could not download " + url + ": " + e + "\n"
                + "Check your internet connection, or provide the server jar yourself with --singularity-server-jar=<path>.", e);
    }

    /** Downloads, verifies and moves the jar into place; returns the SHA-1 of the file. */
    private static String fetch(HttpClient client, Download download, Path dir, Path target) throws LaunchException {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new LaunchException("Could not create " + dir + ": " + e.getMessage(), e);
        }
        String sizeText = download.size() > 0 ? String.format(Locale.ROOT, " (%.1f MB)", download.size() / 1_048_576.0) : "";
        String what = download.versionId().startsWith("url-") ? "the Minecraft server" : "the Minecraft " + download.versionId() + " server";
        LOG.info("Minecraft Servers Launch Jar Is Missing, Generating A New One. Downloading {}{} to {}", what, sizeText, target);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "download-", ".tmp");
            HttpResponse<InputStream> response = client.send(request(download.url().toString()), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new LaunchException("Downloading " + download.url() + " failed: HTTP " + response.statusCode());
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long total = 0;
            try (Log.Progress bar = LOG.progressBytes("Downloading Minecraft Server", Math.max(1, download.size()));
                    InputStream in = response.body(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sha1.update(buf, 0, n);
                    out.write(buf, 0, n);
                    total += n;
                    bar.set(total);
                }
            }
            String actual = HexFormat.of().formatHex(sha1.digest());
            if (download.size() > 0 && total != download.size()) {
                throw new LaunchException("The download is incomplete: got " + total + " bytes but Mojang says " + download.size() + ". Please try again.");
            }
            if (download.sha1() != null && !download.sha1().equals(actual)) {
                throw new LaunchException("The downloaded server jar is corrupt: its SHA-1 is " + actual + " but it should be " + download.sha1() + ". Please try again.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            LOG.info("Download complete and verified (SHA-1 {})", actual);
            return actual;
        } catch (IOException e) {
            throw networkError(download.url().toString(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LaunchException("Interrupted while downloading the server jar.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-1 is mandatory on every JVM
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** A cached jar is valid if its SHA-1 still equals the one recorded when it was downloaded. */
    private static boolean isCachedAndValid(Path jar, Path sidecar) {
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(sidecar)) {
            return false;
        }
        try {
            String recorded = Files.readString(sidecar, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sha1.update(buf, 0, n);
                }
            }
            boolean ok = HexFormat.of().formatHex(sha1.digest()).equals(recorded);
            if (!ok) {
                LOG.warn("The cached server jar {} does not match its recorded hash; downloading it again.", jar);
            }
            return ok;
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
