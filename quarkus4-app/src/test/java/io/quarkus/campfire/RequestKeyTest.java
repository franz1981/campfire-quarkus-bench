package io.quarkus.campfire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import campfire.kit.Headers;
import kotlin.Pair;

/** The request key hashes exactly the bytes the port's {@code request_key} feeds SHA-256 ({@code String.getBytes}, sorted names). */
class RequestKeyTest {
    private static final String[] PIECES = {"a", "Z", "rooms#show", "http://localhost:3000", "/rooms/486777696", "é", "ÿ", "€", "日本", "😀", "\t", " ", ";", "=", ""};

    @Test
    void sameDigestAsGetBytes() throws Exception {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            Headers headers = new Headers();
            int count = random.nextInt(40);
            for (int h = 0; h < count; h++) headers.add(random.nextInt(4) == 0 ? "accept" : text(random, 1 + random.nextInt(3)), text(random, random.nextInt(i % 50 == 0 ? 400 : 6)));
            if (random.nextBoolean()) headers.add("if-none-match", "\"x\"");
            long generation = random.nextLong(), user = random.nextLong(), session = random.nextLong();
            String endpoint = text(random, 2), target = text(random, 3), origin = text(random, 2);
            byte[] expected = reference(generation, endpoint, user, session, target, origin, headers);
            ResponseCache.Key key = ResponseCache.requestKey(generation, endpoint, user, session, target, origin, headers);
            if (expected == null) {
                assertNull(key, "case " + i);
                continue;
            }
            ByteBuffer digest = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(new ResponseCache.Key(digest.getLong(), digest.getLong(), digest.getLong(), digest.getLong()), key, "case " + i);
        }
    }

    private static String text(Random random, int pieces) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pieces; i++) out.append(PIECES[random.nextInt(PIECES.length)]);
        return out.toString();
    }

    /** The port's request key as it was written before: getBytes per field, a TreeSet of names, 8192 bytes at most. */
    private static byte[] reference(long generation, String endpoint, long user, long session, String target, String origin, Headers headers) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] length = {0};
        java.util.function.Consumer<byte[]> field = value -> {
            length[0] += value.length + 8;
            digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.length).array());
            digest.update(value);
        };
        java.util.function.LongConsumer number = value -> field.accept(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        number.accept(generation);
        field.accept(endpoint.getBytes(StandardCharsets.UTF_8));
        number.accept(user);
        number.accept(session);
        field.accept(target.getBytes(StandardCharsets.ISO_8859_1));
        field.accept(origin.getBytes(StandardCharsets.UTF_8));
        TreeSet<String> names = new TreeSet<>();
        for (Pair<? extends String, ? extends String> header : headers) names.add(header.getFirst());
        for (String name : names) {
            if (name.equals("if-none-match") || name.equals("if-modified-since") || name.equals("x-request-id") || name.equals("x-request-start")) continue;
            field.accept(name.getBytes(StandardCharsets.ISO_8859_1));
            for (String value : headers.all(name)) field.accept(value.getBytes(StandardCharsets.ISO_8859_1));
            field.accept(new byte[0]);
        }
        return length[0] > 8192 ? null : digest.digest();
    }
}
