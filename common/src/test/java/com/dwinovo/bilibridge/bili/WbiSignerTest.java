package com.dwinovo.bilibridge.bili;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WbiSignerTest {

    @Test
    void keyFromUrlStripsPathAndExtension() {
        assertEquals("7cd084941338484aae1ad9425b84077c",
                WbiSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"));
    }

    @Test
    void mixinKeyMatchesKnownVector() {
        // Hand-computed from the fixed index table over imgKey+subKey.
        assertEquals("ea1db124af3c7062474693fa704f4ff8",
                WbiSigner.mixinKey("7cd084941338484aae1ad9425b84077c",
                                   "4932caff0ff746eab6f01bf08b70ac45"));
    }

    @Test
    void signedQuerySortsKeysAndAppendsRid() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "0");
        params.put("id", "42");
        String q = WbiSigner.signedQuery(params, "ea1db124af3c7062474693fa704f4ff8", 1700000000L);
        assertTrue(q.startsWith("id=42&type=0&wts=1700000000&w_rid="), q);
        String rid = q.substring(q.indexOf("w_rid=") + 6);
        assertEquals(32, rid.length());
        assertTrue(rid.matches("[0-9a-f]{32}"));
    }

    @Test
    void signedQueryEncodesSpacesAsPercent20AndStripsBannedChars() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("a", "x y!'()*z");
        String q = WbiSigner.signedQuery(params, "ea1db124af3c7062474693fa704f4ff8", 1L);
        assertTrue(q.startsWith("a=x%20yz&"), q);
    }
}
