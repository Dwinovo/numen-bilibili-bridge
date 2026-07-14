package com.dwinovo.bilibridge.bili;

import com.dwinovo.bilibridge.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four REST calls that precede a danmaku WebSocket connection: resolve the
 * real room id, obtain a {@code buvid3} device cookie, fetch + cache the WBI
 * signing keys, and finally fetch the danmaku token + host list. All calls run on
 * the bridge's own network thread — never on a game thread. Protocol details are
 * written down in {@code docs/protocol.md}.
 */
public final class BiliApi {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    /** WBI keys rotate roughly daily; re-fetch after this long. */
    private static final long WBI_KEY_TTL_MS = 12 * 60 * 60 * 1000L;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Optional login cookie — empty string means anonymous. Set before each connect. */
    private volatile String sessdata = "";

    private String buvid3;
    private String mixinKey;
    private long mixinKeyFetchedAtMs;
    private long uid;   // 0 when anonymous; the logged-in mid when SESSDATA is set

    /** Everything the WebSocket stage needs. */
    public record DanmuInfo(long realRoomId, long uid, String buvid3, String token, List<Host> hosts) {}

    public record Host(String host, int wssPort) {}

    public void setSessdata(String sessdata) {
        this.sessdata = sessdata == null ? "" : sessdata.trim();
    }

    /** Run the whole REST prelude for {@code roomId} (short id accepted). Blocking. */
    public DanmuInfo prepare(long roomId) throws IOException, InterruptedException {
        long realRoomId = resolveRealRoomId(roomId);
        ensureBuvid3();
        ensureWbiKeys();
        return fetchDanmuInfo(realRoomId);
    }

    /** Drop cached device/signing state so the next {@link #prepare} starts from scratch. */
    public synchronized void invalidate() {
        buvid3 = null;
        mixinKey = null;
        mixinKeyFetchedAtMs = 0;
        uid = 0;
    }

    /** Step 1 — the room id in the URL may be a "short id"; the stream needs the real one. */
    long resolveRealRoomId(long roomId) throws IOException, InterruptedException {
        JsonObject data = getJson("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId,
                false).getAsJsonObject("data");
        return data.get("room_id").getAsLong();
    }

    /** Step 2 — a buvid3 device cookie; connections without one are dropped seconds after auth. */
    private synchronized void ensureBuvid3() throws IOException, InterruptedException {
        if (buvid3 != null) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.bilibili.com/"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        for (String setCookie : resp.headers().allValues("set-cookie")) {
            if (setCookie.startsWith("buvid3=")) {
                buvid3 = setCookie.substring("buvid3=".length(), cookieEnd(setCookie));
                return;
            }
        }
        throw new IOException("bilibili.com did not hand out a buvid3 cookie");
    }

    private static int cookieEnd(String setCookie) {
        int semi = setCookie.indexOf(';');
        return semi >= 0 ? semi : setCookie.length();
    }

    /** Step 3 — WBI signing keys from the nav endpoint (cached ~12 h); also the uid when logged in. */
    private synchronized void ensureWbiKeys() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (mixinKey != null && now - mixinKeyFetchedAtMs < WBI_KEY_TTL_MS) return;
        JsonObject root = getJson("https://api.bilibili.com/x/web-interface/nav", true);
        JsonObject data = root.getAsJsonObject("data");
        JsonObject wbiImg = data.getAsJsonObject("wbi_img");
        String imgKey = WbiSigner.keyFromUrl(wbiImg.get("img_url").getAsString());
        String subKey = WbiSigner.keyFromUrl(wbiImg.get("sub_url").getAsString());
        mixinKey = WbiSigner.mixinKey(imgKey, subKey);
        mixinKeyFetchedAtMs = now;
        // Anonymous nav still serves wbi_img (code -101); mid only exists when logged in.
        uid = !sessdata.isEmpty() && data.has("mid") && !data.get("mid").isJsonNull()
                ? data.get("mid").getAsLong() : 0;
    }

    /** Step 4 — WBI-signed getDanmuInfo: the auth token + the WebSocket host list. */
    private DanmuInfo fetchDanmuInfo(long realRoomId) throws IOException, InterruptedException {
        String query;
        synchronized (this) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("id", Long.toString(realRoomId));
            params.put("type", "0");
            query = WbiSigner.signedQuery(params, mixinKey, System.currentTimeMillis() / 1000);
        }
        JsonObject root = getJson(
                "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + query, true);
        JsonObject data = root.getAsJsonObject("data");
        String token = data.get("token").getAsString();
        List<Host> hosts = new ArrayList<>();
        JsonArray hostList = data.getAsJsonArray("host_list");
        for (JsonElement e : hostList) {
            JsonObject h = e.getAsJsonObject();
            hosts.add(new Host(h.get("host").getAsString(), h.get("wss_port").getAsInt()));
        }
        if (hosts.isEmpty()) throw new IOException("getDanmuInfo returned an empty host_list");
        return new DanmuInfo(realRoomId, uid, buvid3, token, hosts);
    }

    /** GET returning the parsed body after checking both HTTP status and the api's {@code code} field. */
    private JsonObject getJson(String url, boolean withCookies) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Referer", "https://live.bilibili.com/")
                .GET();
        if (withCookies) {
            String cookie = cookieHeader();
            if (!cookie.isEmpty()) b.header("Cookie", cookie);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + host(url));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        int code = root.has("code") ? root.get("code").getAsInt() : 0;
        // nav answers code -101 ("not logged in") for anonymous callers but still carries wbi_img.
        if (code != 0 && code != -101) {
            String msg = root.has("message") ? root.get("message").getAsString() : "";
            throw new IOException("api code " + code + " (" + msg + ") from " + host(url));
        }
        return root;
    }

    private synchronized String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        if (buvid3 != null) sb.append("buvid3=").append(buvid3);
        if (!sessdata.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("SESSDATA=").append(sessdata);
        }
        return sb.toString();
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            Constants.LOG.debug("[bilibridge] unparseable url in error path");
            return "?";
        }
    }
}
