///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.xerial:sqlite-jdbc:3.53.2.0

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The realistic workload's data, shared by every app (bench.sh, matrix "realistic"):
 *
 * <pre>
 * prepare SEED OUT_DB OUT_CSV SESSIONS SECRET_KEY_BASE
 *     OUT_DB: a copy of SEED with SESSIONS sessions for its active people (round robin);
 *     OUT_CSV: one row per (session, room its person belongs to): "cookie header,room id,person id".
 * write DB PRESENCE_PER_SEC MESSAGES_PER_SEC MESSAGE_ROOM|all
 *     commits until stopped (SIGTERM): presence (a membership's connected_at, as a page's
 *     PresenceChannel refresh does) and messages as the Rust port creates them
 *     (crates/db/src/models/message.rs): the message, its body (a copy of an existing one), the
 *     message and room touches in one transaction; then its search index row and the unread marks
 *     (Room#receive) in a second. A rate of 0 turns that stream off. Prints its counts on exit.
 * check-cookie SECRET_KEY_BASE TOKEN EXP COOKIE
 *     signs TOKEN with EXP and compares with COOKIE (fixtures/cookie.txt): the signing is Rails'.
 * </pre>
 *
 * Cookies are signed as Rails 7 signs {@code cookies.signed[:session_token]}: the JSON value in
 * a {@code _rails} envelope with expiry and purpose, Base64, {@code --}, hex HMAC-SHA1 with the key
 * PBKDF2-HMAC-SHA256(secret_key_base, "signed cookie", 1000, 64).
 */
public class Workload {
    static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Rooms the Kotlin port and the Quarkus app render: none of the 40 messages on the room's page
     * has an attachment or a sound ({@code /play}), which they don't port. bench.sh then compares
     * every (person, room) page with the Rust port's, so a miss here stops the run.
     */
    static final String RENDERED_ROOMS = """
            SELECT r.id FROM rooms r WHERE NOT EXISTS (
              SELECT 1 FROM messages m WHERE m.room_id = r.id
                AND (SELECT count(*) FROM messages n WHERE n.room_id = m.room_id AND n.created_at > m.created_at) < 40
                AND (EXISTS (SELECT 1 FROM active_storage_attachments a WHERE a.record_type = 'Message' AND a.record_id = m.id AND a.name = 'attachment')
                  OR EXISTS (SELECT 1 FROM action_text_rich_texts t WHERE t.record_type = 'Message' AND t.record_id = m.id AND t.body LIKE '%/play %')))""";

    /** Bodies a new message may copy, with their search text: no sound, from a message without an attachment, in a rendered room. */
    static final String COPYABLE_BODIES = "SELECT t.body, (SELECT i.body FROM message_search_index i WHERE i.rowid = m.id) FROM action_text_rich_texts t JOIN messages m ON m.id = t.record_id WHERE t.record_type = 'Message' AND t.name = 'body'"
            + " AND t.body IS NOT NULL AND t.body NOT LIKE '%/play %' AND m.room_id IN (" + RENDERED_ROOMS + ")"
            + " AND NOT EXISTS (SELECT 1 FROM active_storage_attachments a WHERE a.record_type = 'Message' AND a.record_id = m.id)";

    public static void main(String[] args) throws Exception {
        switch (args.length > 0 ? args[0] : "") {
            case "prepare" -> prepare(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Integer.parseInt(args[4]), args[5]);
            case "write" -> write(args[1], Double.parseDouble(args[2]), Double.parseDouble(args[3]), args[4]);
            case "check-cookie" -> {
                String signed = "session_token=" + sign(args[1], args[2], Instant.parse(args[3]));
                boolean same = signed.equals(args[4].strip());
                System.out.println(same ? "cookie signing: identical to the fixture" : "cookie signing DIFFERS:\n" + signed + "\n" + args[4]);
                if (!same) System.exit(1);
            }
            default -> {
                System.err.println("usage: Workload prepare|write|check-cookie ... (see the class comment)");
                System.exit(2);
            }
        }
    }

    // ---- cookies ----------------------------------------------------------------------------

    static String sign(String secretKeyBase, String token, Instant expires) throws Exception {
        byte[] key = pbkdf2Sha256(secretKeyBase.getBytes(StandardCharsets.UTF_8), "signed cookie".getBytes(StandardCharsets.UTF_8), 1000, 64);
        Base64.Encoder b64 = Base64.getEncoder();
        String message = b64.encodeToString(("\"" + token + "\"").getBytes(StandardCharsets.UTF_8));
        String exp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(expires.atOffset(ZoneOffset.UTC));
        String envelope = "{\"_rails\":{\"message\":\"" + message + "\",\"exp\":\"" + exp + "\",\"pur\":\"cookie.session_token\"}}";
        String data = b64.encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        return data + "--" + HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] out = new byte[length];
        for (int block = 1, at = 0; at < length; block++) {
            mac.update(salt);
            byte[] u = mac.doFinal(new byte[] {(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            int n = Math.min(t.length, length - at);
            System.arraycopy(t, 0, out, at, n);
            at += n;
        }
        return out;
    }

    /** {@code has_secure_token}: 24 characters of Base58. */
    static String token(Random random) {
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    static String now() {
        return DB_TIME.format(LocalDateTime.now(ZoneOffset.UTC));
    }

    // ---- prepare ----------------------------------------------------------------------------

    static void prepare(Path seed, Path outDb, Path outCsv, int sessions, String secret) throws Exception {
        Files.copy(seed, outDb, StandardCopyOption.REPLACE_EXISTING);
        Random random = new Random(42);
        Instant expires = Instant.now().plusSeconds(20L * 365 * 24 * 3600);
        List<String> rows = new ArrayList<>();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + outDb)) {
            db.setAutoCommit(false);
            // people who can sign in: active (status 0), not bots (role 2)
            List<Long> people = longs(db, "SELECT id FROM users WHERE status = 0 AND role != 2 ORDER BY id");
            String time = now();
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO sessions (created_at, ip_address, last_active_at, token, updated_at, user_agent, user_id) VALUES (?, '127.0.0.1', ?, ?, ?, 'curl/8.7.1', ?)");
                 PreparedStatement rooms = db.prepareStatement("SELECT room_id FROM memberships WHERE user_id = ? AND room_id IN (" + RENDERED_ROOMS + ") ORDER BY room_id")) {
                for (int i = 0; i < sessions; i++) {
                    long person = people.get(i % people.size());
                    String token = token(random);
                    insert.setString(1, time);
                    insert.setString(2, DB_TIME.format(LocalDateTime.now(ZoneOffset.UTC).plusDays(1))); // no hourly session refresh (a write) during any run
                    insert.setString(3, token);
                    insert.setString(4, time);
                    insert.setLong(5, person);
                    insert.executeUpdate();
                    String cookie = "session_token=" + sign(secret, token, expires);
                    rooms.setLong(1, person);
                    try (ResultSet r = rooms.executeQuery()) {
                        while (r.next()) rows.add(cookie + "," + r.getLong(1) + "," + person);
                    }
                }
            }
            db.commit();
            System.out.printf("prepared %d sessions for %d people, %d (session, room) pages%n", sessions, people.size(), rows.size());
        }
        Files.write(outCsv, rows);
    }

    static List<Long> longs(Connection db, String sql) throws Exception {
        List<Long> out = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) out.add(r.getLong(1));
        }
        return out;
    }

    // ---- write ------------------------------------------------------------------------------

    static volatile boolean running = true;

    static void write(String path, double presencePerSec, double messagesPerSec, String messageRoom) throws Exception {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
            s.execute("PRAGMA journal_mode = wal");
            s.execute("PRAGMA synchronous = normal");
        }
        db.setAutoCommit(false);
        Random random = new SecureRandom();
        List<Long> memberships = longs(db, "SELECT id FROM memberships");
        // (room, member) pairs that may post: active people only, in MESSAGE_ROOM or any room
        List<long[]> posters = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT m.room_id, m.user_id FROM memberships m JOIN users u ON u.id = m.user_id WHERE u.status = 0 AND u.role != 2 AND m.room_id IN (" + RENDERED_ROOMS + ")"
                + (messageRoom.equals("all") ? "" : " AND m.room_id = " + Long.parseLong(messageRoom)))) {
            while (r.next()) posters.add(new long[] {r.getLong(1), r.getLong(2)});
        }
        // bodies of existing messages (pages all three apps render identically before the run)
        List<String[]> bodies = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(COPYABLE_BODIES)) {
            while (r.next()) bodies.add(new String[] {r.getString(1), r.getString(2) != null ? r.getString(2) : ""});
        }
        db.commit();
        if (messagesPerSec > 0 && (posters.isEmpty() || bodies.isEmpty())) throw new IllegalStateException("no room/member to post as, or no message body to copy");
        PreparedStatement presence = db.prepareStatement("UPDATE memberships SET connected_at = ?, updated_at = ? WHERE id = ?");
        PreparedStatement message = db.prepareStatement("INSERT INTO messages (client_message_id, created_at, creator_id, room_id, updated_at) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
        PreparedStatement body = db.prepareStatement("INSERT INTO action_text_rich_texts (body, created_at, name, record_id, record_type, updated_at) VALUES (?, ?, 'body', ?, 'Message', ?)");
        PreparedStatement touchMessage = db.prepareStatement("UPDATE messages SET updated_at = ? WHERE id = ?");
        PreparedStatement touchRoom = db.prepareStatement("UPDATE rooms SET updated_at = ? WHERE id = ?");
        PreparedStatement index = db.prepareStatement("INSERT INTO message_search_index (rowid, body) VALUES (?, ?)");
        // Room#unread_memberships: members not invisible and not connected in the last 60 s (CONNECTION_TTL)
        PreparedStatement unread = db.prepareStatement("UPDATE memberships SET unread_at = ?, updated_at = ? WHERE room_id = ? AND involvement != 'invisible' AND (connected_at IS NULL OR connected_at < ?) AND user_id != ?");

        long[] counts = new long[3]; // presence, messages, failures
        long[] commitNanos = new long[2]; // total, max
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            LockSupport.unpark(main);
            try {
                main.join(5000);
            } catch (InterruptedException ignored) {
            }
            System.out.printf("writer: %d presence commits, %d message commits, %d failed, commit avg %.1f us, max %.1f ms%n", counts[0], counts[1], counts[2],
                    counts[0] + counts[1] == 0 ? 0.0 : commitNanos[0] / 1e3 / (counts[0] + counts[1]), commitNanos[1] / 1e6);
        }));
        // one schedule merging both streams, evenly spaced
        long start = System.nanoTime();
        long presenceEvery = presencePerSec > 0 ? (long) (1e9 / presencePerSec) : Long.MAX_VALUE;
        long messageEvery = messagesPerSec > 0 ? (long) (1e9 / messagesPerSec) : Long.MAX_VALUE;
        // a stream at 0/s is never due
        long nextPresence = presencePerSec > 0 ? start : Long.MAX_VALUE, nextMessage = messagesPerSec > 0 ? start : Long.MAX_VALUE;
        while (running) {
            boolean isMessage = nextMessage <= nextPresence;
            long due = isMessage ? nextMessage : nextPresence;
            if (due == Long.MAX_VALUE) {
                LockSupport.parkNanos(100_000_000L);
                continue;
            }
            long wait = due - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
                continue;
            }
            long t0 = System.nanoTime();
            try {
                String time = now();
                if (isMessage) {
                    long[] poster = posters.get(random.nextInt(posters.size()));
                    String[] copied = bodies.get(random.nextInt(bodies.size()));
                    message.setString(1, UUID.randomUUID().toString());
                    message.setString(2, time);
                    message.setLong(3, poster[1]);
                    message.setLong(4, poster[0]);
                    message.setString(5, time);
                    message.executeUpdate();
                    long id;
                    try (ResultSet keys = message.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                    body.setString(1, copied[0]);
                    body.setString(2, time);
                    body.setLong(3, id);
                    body.setString(4, time);
                    body.executeUpdate();
                    touchMessage.setString(1, time);
                    touchMessage.setLong(2, id);
                    touchMessage.executeUpdate();
                    touchRoom.setString(1, time);
                    touchRoom.setLong(2, poster[0]);
                    touchRoom.executeUpdate();
                    db.commit();
                    // after commit (a transaction of its own): the search index and Room#receive's unread marks
                    index.setLong(1, id);
                    index.setString(2, copied[1]);
                    index.executeUpdate();
                    unread.setString(1, time);
                    unread.setString(2, time);
                    unread.setLong(3, poster[0]);
                    unread.setString(4, DB_TIME.format(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(60)));
                    unread.setLong(5, poster[1]);
                    unread.executeUpdate();
                } else {
                    presence.setString(1, time);
                    presence.setString(2, time);
                    presence.setLong(3, memberships.get(random.nextInt(memberships.size())));
                    presence.executeUpdate();
                }
                db.commit();
                counts[isMessage ? 1 : 0]++;
            } catch (Exception e) {
                counts[2]++;
                try {
                    db.rollback();
                } catch (Exception ignored) {
                }
            }
            long d = System.nanoTime() - t0;
            commitNanos[0] += d;
            commitNanos[1] = Math.max(commitNanos[1], d);
            // on schedule; after a stall longer than a second, start again from now instead of catching up in a burst
            long now = System.nanoTime();
            if (isMessage) nextMessage = now - (nextMessage + messageEvery) > 1_000_000_000L ? now : nextMessage + messageEvery;
            else nextPresence = now - (nextPresence + presenceEvery) > 1_000_000_000L ? now : nextPresence + presenceEvery;
        }
        db.close();
    }
}
