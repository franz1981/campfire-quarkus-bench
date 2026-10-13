package io.quarkus.campfire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import campfire.rails.Cookies;
import campfire.rails.Secrets;

/** {@link SignedCookies} answers exactly as the Kotlin port's {@code Cookies.verifySigned}, and its fast path does answer the plain case. */
class SignedCookiesTest {
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0123456789";
    private static final String[] ODD = {"", "a\"b", "back\\slash", "sl/ash", "é", "日本", "tab\there", "\u0000", "+=/", "{\"_rails\":1}"};

    @Test
    void sameAnswersAsTheKotlinPort() {
        Random random = new Random(11);
        Secrets secrets = new Secrets(hex(random, 128));
        SignedCookies cookies = new SignedCookies(secrets);
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        int compared = 0, fast = 0, plainValid = 0;
        for (int i = 0; i < 3000; i++) {
            String value = random.nextInt(5) == 0 ? ODD[random.nextInt(ODD.length)] : token(random);
            Instant expiresAt = switch (random.nextInt(4)) {
                case 0 -> null;
                case 1 -> now.minusSeconds(random.nextInt(100000));
                default -> now.plusSeconds(1 + random.nextInt(1_000_000_000)).plusMillis(random.nextInt(1000));
            };
            String name = random.nextInt(4) == 0 ? "other" : "session_token";
            String signed = Cookies.INSTANCE.sign(secrets, name, value, expiresAt);
            for (String raw : variants(random, signed)) {
                String expected = Cookies.INSTANCE.verifySigned(secrets, "session_token", raw, now);
                String actual = cookies.verify("session_token", raw, now);
                assertEquals(expected, actual, "case " + i + ": " + raw);
                compared++;
                if (cookies.fastPath("session_token", raw, now) != null) fast++;
            }
            if (name.equals("session_token") && expiresAt != null && expiresAt.isAfter(now) && value.matches("[A-Za-z0-9]+")) plainValid++;
        }
        assertTrue(compared > 10000, "compared " + compared);
        assertTrue(fast > 0.9 * plainValid, "fast path answered " + fast + " of " + plainValid + " plain valid cookies");
    }

    /** The cookie as signed, and tampered with in the ways that must not verify (or must verify the same). */
    private static List<String> variants(Random random, String signed) {
        List<String> out = new ArrayList<>();
        out.add(signed);
        int split = signed.length() - 42;
        char[] payload = signed.toCharArray();
        int at = random.nextInt(Math.max(split, 1));
        payload[at] = payload[at] == 'A' ? 'B' : 'A';
        out.add(new String(payload));
        char[] digest = signed.toCharArray();
        int d = signed.length() - 1 - random.nextInt(40);
        digest[d] = digest[d] == '0' ? '1' : '0';
        out.add(new String(digest));
        out.add(signed.substring(0, split) + signed.substring(split).toUpperCase());
        out.add(signed.substring(0, split).replace("=", "") + signed.substring(split));
        out.add(signed.substring(1));
        out.add(signed + "0");
        return out;
    }

    private static String token(Random random) {
        StringBuilder out = new StringBuilder();
        for (int i = 0, n = 1 + random.nextInt(40); i < n; i++) out.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        return out.toString();
    }

    private static String hex(Random random, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append("0123456789abcdef".charAt(random.nextInt(16)));
        return out.toString();
    }
}
