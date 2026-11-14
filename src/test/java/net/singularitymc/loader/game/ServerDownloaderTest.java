package net.singularitymc.loader.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.net.URI;
import net.singularitymc.loader.launch.LaunchException;
import org.junit.jupiter.api.Test;

class ServerDownloaderTest {
    private static final String MANIFEST = """
            {"latest": {"release": "26.3", "snapshot": "26.4-snapshot-1"},
             "versions": [
               {"id": "26.4-snapshot-1", "type": "snapshot", "url": "https://example.com/snap.json"},
               {"id": "26.3", "type": "release", "url": "https://example.com/26.3.json"}]}""";

    @Test
    void findsVersionsInTheManifest() throws Exception {
        var manifest = JsonParser.parseString(MANIFEST).getAsJsonObject();
        assertEquals("https://example.com/26.3.json", ServerDownloader.findVersionUrl(manifest, "26.3"));
        assertEquals("https://example.com/snap.json", ServerDownloader.findVersionUrl(manifest, "26.4-snapshot-1"));
    }

    @Test
    void latestMeansLatestRelease() throws Exception {
        var manifest = JsonParser.parseString(MANIFEST).getAsJsonObject();
        assertEquals("https://example.com/26.3.json", ServerDownloader.findVersionUrl(manifest, "latest"));
    }

    @Test
    void unknownVersionsAreReported() {
        var manifest = JsonParser.parseString(MANIFEST).getAsJsonObject();
        assertThrows(LaunchException.class, () -> ServerDownloader.findVersionUrl(manifest, "1.2.3"));
    }

    @Test
    void readsTheServerDownloadEntry() throws Exception {
        var json = JsonParser.parseString("""
                {"id": "26.3", "downloads": {"server": {"sha1": "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                 "size": 12345, "url": "https://piston-data.mojang.com/v1/objects/abcdef0123456789abcdef0123456789abcdef01/server.jar"}}}""").getAsJsonObject();
        var d = ServerDownloader.parseServerDownload(json);
        assertEquals("26.3", d.versionId());
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", d.sha1());
        assertEquals(12345, d.size());
    }

    @Test
    void versionsWithoutServerDownloadAreRejected() {
        var json = JsonParser.parseString("{\"id\": \"x\", \"downloads\": {\"client\": {\"url\": \"u\"}}}").getAsJsonObject();
        assertThrows(LaunchException.class, () -> ServerDownloader.parseServerDownload(json));
    }

    @Test
    void extractsTheHashFromMojangObjectUrls() {
        assertEquals("33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
                ServerDownloader.sha1FromObjectUrl(URI.create("https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar")));
        assertEquals(null, ServerDownloader.sha1FromObjectUrl(URI.create("https://example.com/files/server.jar")));
    }
}
