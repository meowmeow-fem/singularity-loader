package net.singularitymc.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks Mojang whether a name belongs to a paid account. Only the name is sent (as any online-mode server sends it
 * for every login). Answers are remembered: "paid" for an hour, "free" for ten minutes, so a busy login screen
 * does not run into Mojang's request limit; "unknown" (Mojang did not answer) is never remembered.
 */
final class PremiumLookup {
    enum Result { PAID, FREE, UNKNOWN }

    private record Cached(Result result, long until) {}

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile String url;
    private volatile Duration timeout;

    PremiumLookup(String url, int timeoutSeconds) {
        configure(url, timeoutSeconds);
    }

    void configure(String lookupUrl, int timeoutSeconds) {
        this.url = lookupUrl;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(30, timeoutSeconds)));
        cache.clear();
    }

    CompletableFuture<Result> lookup(String name) {
        String key = AccountStore.key(name);
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.until() > now) {
            return CompletableFuture.completedFuture(cached.result());
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.replace("{name}", URLEncoder.encode(name, StandardCharsets.UTF_8))))
                    .timeout(timeout).header("Accept", "application/json").GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(Result.UNKNOWN);
        }
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
            Result result = error != null ? Result.UNKNOWN : fromStatus(response.statusCode());
            if (result != Result.UNKNOWN) {
                if (cache.size() > 10_000) {
                    cache.clear();
                }
                cache.put(key, new Cached(result, System.currentTimeMillis() + (result == Result.PAID ? 3_600_000 : 600_000)));
            }
            return result;
        });
    }

    static Result fromStatus(int status) {
        if (status == 200) {
            return Result.PAID;
        }
        if (status == 204 || status == 404) {
            return Result.FREE;
        }
        return Result.UNKNOWN; // rate limited, down, blocked: we do not know, and say so
    }
}
