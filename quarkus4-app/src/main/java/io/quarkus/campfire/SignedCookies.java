package io.quarkus.campfire;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import campfire.rails.Cookies;
import campfire.rails.Secrets;

/**
 * {@code cookies.signed[name]} for a string value, as Rails' cookie jar signs it today: the legacy
 * envelope {@code {"_rails":{"message":"<base64 of the JSON value>","exp":"<ISO time>","pur":"cookie.<name>"}}},
 * Base64-encoded and followed by {@code --} and the hex HMAC-SHA1 of that Base64. A Java port of the
 * Kotlin port's {@code campfire.rails.Cookies.verifySigned} path ({@code MessageVerifier},
 * {@code Metadata}, {@code RubyBase64}; <a href="https://github.com/mysticcoders/once-campfire-kotlin">once-campfire-kotlin</a>
 * at 87436f3, MIT license, Copyright (c) 37signals, LLC) for that one case only: same key, same HMAC,
 * same Base64 acceptance, same purpose and expiry checks, without allocating anything but the value
 * (a thread's {@code Mac} and scratch arrays are reused; the envelope's JSON is matched byte for byte
 * instead of parsed). Any other cookie (another envelope or key order, JSON escapes, a bad signature,
 * an expired or unparsable time) goes to {@code Cookies.verifySigned} itself, so the answer is always
 * the Kotlin port's ({@code SignedCookiesTest}).
 */
final class SignedCookies {
    private static final int HEX_LENGTH = 40; // HMAC-SHA1
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final int[] URL_SAFE_VALUES = new int[128];

    static {
        java.util.Arrays.fill(URL_SAFE_VALUES, -1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) URL_SAFE_VALUES[alphabet.charAt(i)] = i;
        URL_SAFE_VALUES['-'] = 62;
        URL_SAFE_VALUES['_'] = 63;
    }

    private static final byte[] PREFIX = "{\"_rails\":{\"message\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AFTER_MESSAGE = "\",\"exp\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AFTER_EXP = "\",\"pur\":\"cookie.".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SUFFIX = "\"}}".getBytes(StandardCharsets.US_ASCII);

    private final Secrets secrets;
    private final byte[] key;
    private final ThreadLocal<Scratch> scratch;

    SignedCookies(Secrets secrets) {
        this.secrets = secrets;
        this.key = secrets.getKeyGenerator().generateKey(Cookies.SIGNED_COOKIE_SALT, 64);
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(key));
    }

    /** A thread's HMAC, already keyed, and its buffers. */
    private static final class Scratch {
        final Mac mac;
        final byte[] digest = new byte[20];
        byte[] encoded = new byte[512];
        byte[] envelope = new byte[512];
        byte[] value = new byte[256];

        Scratch(byte[] key) {
            try {
                mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(key, "HmacSHA1"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** {@code cookies.signed[name]} as a string, or null wherever Rails returns nil. */
    String verify(String name, String raw, Instant now) {
        String value = fastPath(name, raw, now);
        return value != null ? value : Cookies.INSTANCE.verifySigned(secrets, name, raw, now);
    }

    /** The value for the common, valid case; null for anything else (which the Kotlin port then decides). */
    String fastPath(String name, String raw, Instant now) {
        int split = raw.length() - (HEX_LENGTH + 2);
        if (split <= 0 || raw.charAt(split) != '-' || raw.charAt(split + 1) != '-') return null;
        Scratch s = scratch.get();
        // The Base64 text, as the bytes the HMAC covers (ASCII only: anything else is left to the jar).
        if (s.encoded.length < split) s.encoded = new byte[Math.max(split, s.encoded.length * 2)];
        byte[] encoded = s.encoded;
        for (int i = 0; i < split; i++) {
            char c = raw.charAt(i);
            if (c >= 128) return null;
            encoded[i] = (byte) c;
        }
        s.mac.update(encoded, 0, split);
        try {
            s.mac.doFinal(s.digest, 0);
        } catch (GeneralSecurityException e) {
            return null;
        }
        int difference = 0;
        for (int i = 0; i < s.digest.length; i++) {
            int b = s.digest[i];
            difference |= raw.charAt(split + 2 + 2 * i) ^ HEX[(b >> 4) & 0xf];
            difference |= raw.charAt(split + 3 + 2 * i) ^ HEX[b & 0xf];
        }
        if (difference != 0) return null;
        // The envelope: RubyBase64.urlsafeDecode of the Base64 text.
        int envelopeLength = decodedLength(encoded, split);
        if (envelopeLength < 0) return null;
        if (s.envelope.length < envelopeLength) s.envelope = new byte[Math.max(envelopeLength, s.envelope.length * 2)];
        byte[] envelope = s.envelope;
        if (!decode(encoded, 0, split, envelope)) return null;
        // {"_rails":{"message":"<b64>","exp":"<24-character time>","pur":"cookie.<name>"}}, exactly.
        if (!startsWith(envelope, 0, envelopeLength, PREFIX)) return null;
        int messageStart = PREFIX.length;
        int at = messageStart;
        while (at < envelopeLength && envelope[at] != '"') {
            int c = envelope[at];
            if (c < 0 || URL_SAFE_VALUES[c] < 0 && c != '=') return null;
            at++;
        }
        int messageEnd = at;
        if (!startsWith(envelope, at, envelopeLength, AFTER_MESSAGE)) return null;
        at += AFTER_MESSAGE.length;
        if (at + 24 > envelopeLength) return null;
        Instant expiresAt = parseTime(envelope, at);
        if (expiresAt == null || !now.isBefore(expiresAt)) return null;
        at += 24;
        if (!startsWith(envelope, at, envelopeLength, AFTER_EXP)) return null;
        at += AFTER_EXP.length;
        for (int i = 0; i < name.length(); i++, at++) {
            if (at >= envelopeLength || envelope[at] != name.charAt(i)) return null;
        }
        if (at + SUFFIX.length != envelopeLength || !startsWith(envelope, at, envelopeLength, SUFFIX)) return null;
        // The message: RubyBase64.urlsafeDecode, then a JSON string with nothing to unescape.
        int valueLength = decodedLength(envelope, messageStart, messageEnd);
        if (valueLength < 2) return null;
        if (s.value.length < valueLength) s.value = new byte[Math.max(valueLength, s.value.length * 2)];
        byte[] value = s.value;
        if (!decode(envelope, messageStart, messageEnd, value)) return null;
        if (value[0] != '"' || value[valueLength - 1] != '"') return null;
        for (int i = 1; i < valueLength - 1; i++) {
            int c = value[i];
            if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') return null;
        }
        return new String(value, 1, valueLength - 2, StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] bytes, int at, int length, byte[] prefix) {
        if (at + prefix.length > length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[at + i] != prefix[i]) return false;
        return true;
    }

    /** {@code Time} as {@code Metadata.parseTime} reads this envelope's: {@code YYYY-MM-DDTHH:MM:SS.mmmZ}; null otherwise. */
    private static Instant parseTime(byte[] b, int at) {
        if (b[at + 4] != '-' || b[at + 7] != '-' || b[at + 10] != 'T' || b[at + 13] != ':' || b[at + 16] != ':' || b[at + 19] != '.' || b[at + 23] != 'Z') return null;
        int year = digits(b, at, 4), month = digits(b, at + 5, 2), day = digits(b, at + 8, 2);
        int hour = digits(b, at + 11, 2), minute = digits(b, at + 14, 2), second = digits(b, at + 17, 2), millis = digits(b, at + 20, 3);
        if ((year | month | day | hour | minute | second | millis) < 0) return null;
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static int digits(byte[] b, int at, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int d = b[at + i] - '0';
            if (d < 0 || d > 9) return -1;
            value = value * 10 + d;
        }
        return value;
    }

    private static int decodedLength(byte[] text, int length) {
        return decodedLength(text, 0, length);
    }

    /**
     * {@code RubyBase64.urlsafeDecode}'s output length for {@code text[from, to)}, or -1 where it
     * returns null: implicit padding when there is no {@code =} and the length isn't a multiple of
     * four, at most two {@code =} in all.
     */
    private static int decodedLength(byte[] text, int from, int to) {
        int length = to - from;
        int implicitPadding = (length > 0 && text[to - 1] == '=') || length % 4 == 0 ? 0 : 4 - length % 4;
        int total = length + implicitPadding;
        if (total % 4 != 0 || total == 0) return -1;
        int explicitPadding = 0;
        while (explicitPadding < 3 && explicitPadding < length && text[to - 1 - explicitPadding] == '=') explicitPadding++;
        int padding = explicitPadding + implicitPadding;
        if (padding > 2) return -1;
        return total / 4 * 3 - padding;
    }

    /** {@code RubyBase64.urlsafeDecode}'s canonical decode of {@code text[from, to)} into {@code out}; false where it returns null. */
    private static boolean decode(byte[] text, int from, int to, byte[] out) {
        int end = to;
        while (end > from && text[end - 1] == '=') end--;
        int buffer = 0, bits = 0, o = 0;
        for (int i = from; i < end; i++) {
            int c = text[i];
            int value = c >= 0 && c < 128 ? URL_SAFE_VALUES[c] : -1;
            if (value < 0) return false;
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        return buffer == 0;
    }
}
