package com.dwinovo.bilibridge.bili;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * WBI request signing (mandatory on {@code getDanmuInfo} since 2025-05). Pure
 * functions, no I/O: the caller fetches {@code img_url}/{@code sub_url} from the
 * nav endpoint and hands the two key strings in. Full derivation is documented in
 * {@code docs/protocol.md}.
 */
public final class WbiSigner {

    /**
     * Fixed shuffle table: the 32-char mixin key is built by picking these indices
     * out of the 64-char concatenation {@code imgKey + subKey}.
     */
    private static final int[] MIXIN_TABLE = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    };

    private WbiSigner() {}

    /** Extract the bare key from a wbi_img url: strip directories and the file extension. */
    public static String keyFromUrl(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    /** Derive the 32-char mixin key from the two nav keys. */
    public static String mixinKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for (int idx : MIXIN_TABLE) {
            sb.append(raw.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * Sign {@code params} (must NOT already contain {@code wts}/{@code w_rid}) and
     * return the complete query string: keys sorted, values sanitized and
     * percent-encoded, {@code wts} added, {@code w_rid} appended.
     */
    public static String signedQuery(Map<String, String> params, String mixinKey, long epochSeconds) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", Long.toString(epochSeconds));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(encode(e.getKey())).append('=').append(encode(sanitize(e.getValue())));
        }
        String wRid = md5Hex(query + mixinKey);
        return query + "&w_rid=" + wRid;
    }

    /** The signing algorithm strips these characters from values before encoding. */
    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("!'()*".indexOf(c) < 0) sb.append(c);
        }
        return sb.toString();
    }

    private static String encode(String s) {
        // URLEncoder is form-encoding; the space must be %20 for the signature to match.
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String md5Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("JDK without MD5", e);   // cannot happen on a compliant JDK
        }
    }
}
