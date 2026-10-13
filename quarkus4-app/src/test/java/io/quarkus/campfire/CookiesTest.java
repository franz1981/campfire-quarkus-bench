package io.quarkus.campfire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import campfire.kit.CookieCrypto;
import campfire.kit.CookieJar;

/** The session cookie scan finds what the kit's jar holds for {@code session_token}, whenever the jar wouldn't unescape it. */
class CookiesTest {
    private static final String[] NAMES = {"session_token", "session_token ", " session_token", "Session_token", "session_tokens", "a", "_campfire", ""};
    private static final String[] VALUES = {"eyJfcmFpbHMi--958d96", "", "x=y", "a%3Db", "a+b", " v ", "--", "\"q\""};
    private static final CookieCrypto CRYPTO = (CookieCrypto) Proxy.newProxyInstance(CookiesTest.class.getClassLoader(), new Class<?>[] {CookieCrypto.class},
            (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });

    @Test
    void sameValueAsTheJar() {
        Random random = new Random(7);
        int compared = 0;
        for (int i = 0; i < 20000; i++) {
            List<String> headers = new ArrayList<>();
            for (int h = 0, n = 1 + random.nextInt(3); h < n; h++) {
                StringBuilder header = new StringBuilder();
                for (int p = 0, pairs = random.nextInt(5); p < pairs; p++) {
                    if (p > 0) header.append(';').append(" ".repeat(random.nextInt(3)));
                    header.append(NAMES[random.nextInt(NAMES.length)]);
                    if (random.nextInt(5) > 0) header.append('=').append(VALUES[random.nextInt(VALUES.length)]);
                }
                headers.add(header.toString());
            }
            String expected = CookieJar.Companion.fromHeaders(headers, CRYPTO, Clock.systemUTC()).get("session_token");
            String scanned = HttpRoutes.sessionCookie(headers);
            if (scanned != null && (scanned.indexOf('%') >= 0 || scanned.indexOf('+') >= 0)) continue; // left to the jar
            assertEquals(expected, scanned, "case " + i + ": " + headers);
            compared++;
        }
        assertEquals(true, compared > 15000, "compared " + compared);
    }
}
