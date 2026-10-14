package io.quarkus.campfire;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import campfire.db.Boost;
import campfire.db.LayoutAccount;
import campfire.db.Message;
import campfire.db.MessageKey;
import campfire.db.PageMessage;
import campfire.db.Room;
import campfire.db.RoomKind;
import campfire.db.Session;
import campfire.db.Ts;
import campfire.db.User;

/**
 * The room page's SQL through SQLite's C API ({@link Sqlite}, FFM): one reader connection
 * ({@code query_only}) and one writer connection, both opened and used only by the
 * {@link SqliteStage} thread, plus the response cache's {@code data_version} observer (read once at
 * startup, then only there too). Other threads get their reads and writes done there. The statements
 * are the ones {@code crates/db} sends, prepared once and kept, as rusqlite's {@code prepare_cached}
 * and the Kotlin port's readers do; connections get the settings Rails applies.
 */
@Singleton
public class Db {
    static final String SESSION_COLUMNS = "\"sessions\".\"id\", \"sessions\".\"user_id\", \"sessions\".\"token\", \"sessions\".\"ip_address\", \"sessions\".\"user_agent\", \"sessions\".\"last_active_at\", \"sessions\".\"created_at\", \"sessions\".\"updated_at\"";
    static final String USER_COLUMNS = "\"users\".\"id\", \"users\".\"name\", \"users\".\"email_address\", \"users\".\"password_digest\", \"users\".\"role\", \"users\".\"status\", \"users\".\"bio\", \"users\".\"bot_token\", \"users\".\"created_at\", \"users\".\"updated_at\"";
    static final String ROOM_COLUMNS = "\"rooms\".\"id\", \"rooms\".\"name\", \"rooms\".\"type\", \"rooms\".\"creator_id\", \"rooms\".\"created_at\", \"rooms\".\"updated_at\"";
    static final String MESSAGE_COLUMNS = "\"messages\".\"id\", \"messages\".\"room_id\", \"messages\".\"creator_id\", \"messages\".\"client_message_id\", \"messages\".\"created_at\", \"messages\".\"updated_at\"";
    static final String ACCOUNT_COLUMNS = "\"accounts\".\"id\", \"accounts\".\"name\", \"accounts\".\"join_code\", \"accounts\".\"custom_styles\", \"accounts\".\"settings\", \"accounts\".\"singleton_guard\", \"accounts\".\"created_at\", \"accounts\".\"updated_at\"";

    static final String SESSION_BY_TOKEN = "SELECT " + SESSION_COLUMNS + " FROM \"sessions\" WHERE \"sessions\".\"token\" = ? LIMIT 1";
    static final String USER_BY_ID = "SELECT " + USER_COLUMNS + " FROM \"users\" WHERE \"users\".\"id\" = ? LIMIT 1";
    static final String ROOM_FOR_USER = "SELECT " + ROOM_COLUMNS + " FROM \"rooms\" INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ? AND \"rooms\".\"id\" = ? LIMIT 1";
    static final String FIRST_ROOM_FOR_USER = "SELECT " + ROOM_COLUMNS + " FROM \"rooms\" INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ? ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1";
    static final String ROOM_BY_ID = "SELECT " + ROOM_COLUMNS + " FROM \"rooms\" WHERE \"rooms\".\"id\" = ? LIMIT 1";
    static final String ORIGINAL_ROOM = "SELECT " + ROOM_COLUMNS + " FROM \"rooms\" ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1";
    static final String ROOM_USERS = "SELECT " + USER_COLUMNS + " FROM \"users\" INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ?";
    static final String LAST_PAGE = "SELECT " + MESSAGE_COLUMNS + " FROM \"messages\" WHERE \"messages\".\"room_id\" = ? ORDER BY \"messages\".\"created_at\" DESC LIMIT 40";
    static final String PAGED = "SELECT 1 FROM \"messages\" WHERE \"messages\".\"room_id\" = ? LIMIT 1 OFFSET 40";
    static final String FIRST_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS + " FROM \"accounts\" ORDER BY \"accounts\".\"id\" ASC LIMIT 1";
    static final String RICH_TEXT_BODY = "SELECT \"action_text_rich_texts\".\"id\", \"action_text_rich_texts\".\"name\", \"action_text_rich_texts\".\"body\", \"action_text_rich_texts\".\"record_type\", \"action_text_rich_texts\".\"record_id\", \"action_text_rich_texts\".\"created_at\", \"action_text_rich_texts\".\"updated_at\" FROM \"action_text_rich_texts\" WHERE \"action_text_rich_texts\".\"record_id\" = ? AND \"action_text_rich_texts\".\"record_type\" = ? AND \"action_text_rich_texts\".\"name\" = ? LIMIT 1";
    static final String ATTACHED_BLOB = "SELECT b.id, b.key, b.filename, b.content_type, b.metadata, b.service_name, b.byte_size, b.checksum, b.created_at FROM active_storage_blobs b JOIN active_storage_attachments a ON a.blob_id = b.id WHERE a.record_type = ?1 AND a.record_id = ?2 AND a.name = ?3 ORDER BY a.id LIMIT 1";
    static final String BOOSTS_FOR_MESSAGE = "SELECT \"boosts\".\"id\", \"boosts\".\"message_id\", \"boosts\".\"booster_id\", \"boosts\".\"content\", \"boosts\".\"created_at\", \"boosts\".\"updated_at\" FROM \"boosts\" WHERE \"boosts\".\"message_id\" = ? ORDER BY \"boosts\".\"created_at\" ASC";
    static final String BANNED_IP = "SELECT 1 AS one FROM \"bans\" WHERE \"bans\".\"ip_address\" = ? LIMIT 1";
    static final String BOT_BY_KEY = "SELECT " + USER_COLUMNS + " FROM \"users\" WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" = 2 AND \"users\".\"id\" = ? AND \"users\".\"bot_token\" = ? LIMIT 1";
    /**
     * The indexes the Rust port adds to the Rails schema, created at boot when missing
     * ({@code crates/db/src/schema.rs} {@code ADDITIONS}, run by {@code schema::prepare}): without the
     * first, every room page sorts the room's whole history.
     */
    static final List<String> SCHEMA_ADDITIONS = List.of(
            "CREATE INDEX IF NOT EXISTS \"index_messages_on_room_id_and_created_at\" ON \"messages\" (\"room_id\", \"created_at\")",
            "CREATE INDEX IF NOT EXISTS \"index_messages_on_room_id_and_updated_at\" ON \"messages\" (\"room_id\", \"updated_at\")");
    static final String TOUCH_SESSION = "UPDATE \"sessions\" SET \"ip_address\" = ?, \"last_active_at\" = ?, \"updated_at\" = ?, \"user_agent\" = ? WHERE \"sessions\".\"id\" = ?";

    @ConfigProperty(name = "campfire.database-path")
    String path;

    /** {@code libcampfire_sqlite3}: the Rust port's SQLite build (the Kotlin port's {@code native/build.sh}). */
    @ConfigProperty(name = "campfire.sqlite-lib")
    String library;

    private Sqlite sqlite;
    /** The thread that owns the connections, and the stage that runs work there for other threads. */
    private volatile Thread owner;
    private SqliteStage stage;
    /** The reads of any other thread: each one forwarded to the stage. */
    private Reads forwarding;
    /** On a request pool thread, the room page being rendered: its reads, read at once ({@link #prefetchRoomPage}). */
    private static final ThreadLocal<RoomPageReads> PAGE = new ThreadLocal<>();
    /** The stage's connections, opened on first use there and kept (only that thread uses them). */
    private Reader reader;
    private Sqlite.Connection writer;
    private Sqlite.Statement touchSession;

    @PostConstruct
    void init() {
        sqlite = new Sqlite(Path.of(library));
        // the Rust port's schema additions, at boot, before any request (its schema::prepare)
        Sqlite.Connection connection = sqlite.open(path, false);
        try {
            configure(connection, false);
            for (String addition : SCHEMA_ADDITIONS) connection.execute(addition);
        } finally {
            connection.close();
        }
    }

    /** Called by the stage before it starts: from then on only {@code owner} touches the connections. */
    void attach(SqliteStage stage, Thread owner) {
        this.stage = stage;
        this.forwarding = new ForwardingReads(stage);
        this.owner = owner;
    }

    /** Throws unless called by the owner thread (or before there is one, at startup). */
    private void assertOwner() {
        Thread o = owner;
        if (o != null && o != Thread.currentThread()) throw new IllegalStateException("SQLite connection used off the SQLite stage by " + Thread.currentThread().getName());
    }

    /**
     * Runs {@code work} with the reads (the Kotlin port's {@code db.read}): on an event loop, the
     * request's prefetched reads ({@link PrefetchedReads}); on the SQLite stage, its connection; on any
     * other thread, the request's prefetched reads if it has some, each other read forwarded to the
     * stage (the thread waits for it). {@code work} itself runs here: only SQL runs on the stage.
     */
    public <T> T read(Function<Reads, T> work) {
        if (io.vertx.core.Context.isOnEventLoopThread()) return work.apply(new PrefetchedReads(Staged.prefetched(), null));
        Thread o = owner;
        if (o == null || o == Thread.currentThread()) return work.apply(reader());
        RoomPageReads page = PAGE.get();
        Reads others = page != null ? page : forwarding;
        SqliteStage.Prefetched p = Staged.current();
        return work.apply(p != null ? new PrefetchedReads(p, others) : others);
    }

    /**
     * On a thread that forwards its reads: the room page's reads in one forwarded read
     * ({@link RoomPageReads#load}), answering this thread's reads until the returned action runs.
     * On an event loop or the stage, nothing (their reads are prefetched, or direct).
     */
    Runnable prefetchRoomPage(Room room, long userId, Long lastRoom, Function<MessageKey, ?> fragment) {
        Thread o = owner;
        if (io.vertx.core.Context.isOnEventLoopThread() || o == null || o == Thread.currentThread()) return () -> {
        };
        RoomPageReads page = stage.read(r -> RoomPageReads.load(r, room, userId, lastRoom, fragment)).with(forwarding);
        PAGE.set(page);
        return PAGE::remove;
    }

    /** The stage's reader connection, opened on first use. */
    Reader reader() {
        assertOwner();
        if (reader == null) {
            Sqlite.Connection connection = sqlite.open(path, false);
            configure(connection, true);
            reader = new Reader(connection);
        }
        return reader;
    }

    /**
     * The settings Rails applies to a connection ({@code SQLite3Adapter#configure_connection}, the Rust
     * port's {@code configure_connection}, the Kotlin port's {@code configureConnection}), plus
     * {@code query_only} for the reader.
     */
    private static void configure(Sqlite.Connection connection, boolean reader) {
        connection.execute("PRAGMA busy_timeout = 5000");
        connection.execute("PRAGMA foreign_keys = ON");
        connection.execute("PRAGMA journal_mode = wal");
        connection.execute("PRAGMA synchronous = normal");
        connection.execute("PRAGMA journal_size_limit = 67108864");
        connection.execute("PRAGMA cache_size = 2000");
        if (reader) connection.execute("PRAGMA query_only = ON");
    }

    /**
     * {@code PRAGMA data_version} on a read-only connection of its own, waiting at most 50 ms for a
     * lock, as Rust's {@code Observer} reads it (for {@link ResponseCache.Store}).
     */
    ResponseCache.DataVersion dataVersion() {
        Sqlite.Connection connection = sqlite.open(path, true);
        connection.execute("PRAGMA busy_timeout = 50");
        Sqlite.Statement pragma = connection.prepare("PRAGMA data_version");
        return () -> {
            assertOwner();
            try {
                if (!pragma.step()) throw new IllegalStateException("PRAGMA data_version returned no row");
                return pragma.integer(1);
            } finally {
                pragma.reset();
            }
        };
    }

    /** Closes the stage's connections (their statements with them), at shutdown. */
    void close() {
        if (reader != null) reader.connection.close();
        if (writer != null) writer.close();
        reader = null;
        writer = null;
    }

    /**
     * {@code Session#refresh_activity}'s update ({@code touchSession}) on the writer connection, on the
     * stage: an event loop's request goes there instead ({@link Staged#needStage}); another thread
     * waits for it.
     */
    public void touchSession(long sessionId, String ipAddress, String userAgent, String now) {
        if (io.vertx.core.Context.isOnEventLoopThread()) Staged.needStage();
        Thread o = owner;
        if (o != null && o != Thread.currentThread()) {
            stage.write(() -> touchSession(sessionId, ipAddress, userAgent, now));
            return;
        }
        if (writer == null) {
            writer = sqlite.open(path, false);
            configure(writer, false);
            touchSession = writer.prepare(TOUCH_SESSION);
        }
        try {
            touchSession.bindText(1, ipAddress);
            touchSession.bindText(2, now);
            touchSession.bindText(3, now);
            touchSession.bindText(4, userAgent);
            touchSession.bindLong(5, sessionId);
            touchSession.step();
        } finally {
            touchSession.reset();
        }
    }

    @FunctionalInterface
    interface Binder {
        void bind(Sqlite.Statement statement);
    }

    @FunctionalInterface
    interface RowReader<T> {
        T read(Sqlite.Statement row);
    }

    /** One reader connection's queries ({@code campfire.db.Reader}'s, row for row). */
    static final class Reader implements Reads {
        private final Sqlite.Connection connection;
        private final Map<String, Sqlite.Statement> prepared = new HashMap<>();
        /** The batch's read transaction, prepared once (no per-batch {@code sqlite3_exec} and its allocations). */
        private final Sqlite.Statement begin;
        private final Sqlite.Statement commit;

        Reader(Sqlite.Connection connection) {
            this.connection = connection;
            begin = connection.prepare("BEGIN");
            commit = connection.prepare("COMMIT");
        }

        /** Starts a read transaction (deferred: its snapshot is taken by the first read). */
        void begin() {
            run(begin);
        }

        void commit() {
            run(commit);
        }

        /** Ends whatever transaction is open; throws when none is. */
        void rollback() {
            connection.execute("ROLLBACK");
        }

        /** Runs a statement that returns no rows. */
        void run(Sqlite.Statement statement) {
            try {
                statement.step();
            } finally {
                statement.reset();
            }
        }

        private Sqlite.Statement statement(String sql) {
            Sqlite.Statement statement = prepared.get(sql);
            if (statement == null) {
                statement = connection.prepare(sql);
                prepared.put(sql, statement);
            }
            return statement;
        }

        private <T> T one(String sql, Binder bind, RowReader<T> read) {
            Sqlite.Statement statement = statement(sql);
            bind.bind(statement);
            try {
                return statement.step() ? read.read(statement) : null;
            } finally {
                statement.reset();
            }
        }

        private <T> List<T> all(String sql, Binder bind, RowReader<T> read) {
            Sqlite.Statement statement = statement(sql);
            bind.bind(statement);
            List<T> out = new ArrayList<>();
            try {
                while (statement.step()) out.add(read.read(statement));
            } finally {
                statement.reset();
            }
            return out;
        }

        @Override
        public boolean banned(String ipAddress) {
            return one(BANNED_IP, s -> s.bindText(1, ipAddress), r -> Boolean.TRUE) != null;
        }

        /** {@code User.authenticate_bot(bot_key)}: an active bot by {@code <id>-<token>}. */
        @Override
        public User authenticateBot(String botKey) {
            String[] parts = botKey.split("-");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
            return one(BOT_BY_KEY, s -> {
                s.bindText(1, parts[0]);
                s.bindText(2, parts[1]);
            }, Reader::readUser);
        }

        @Override
        public Session sessionByToken(String token) {
            return one(SESSION_BY_TOKEN, s -> s.bindText(1, token), r -> new Session(r.integer(1), r.integer(2), r.text(3), ts(r.text(6))));
        }

        @Override
        public User user(long id) {
            return one(USER_BY_ID, s -> s.bindLong(1, id), Reader::readUser);
        }

        @Override
        public Room roomForUser(long userId, long roomId) {
            return one(ROOM_FOR_USER, s -> {
                s.bindLong(1, userId);
                s.bindLong(2, roomId);
            }, Reader::readRoom);
        }

        @Override
        public Long originalRoomId() {
            return one(ORIGINAL_ROOM, s -> {}, r -> r.integer(1));
        }

        @Override
        public Long roomIdForUser(long userId, long roomId) {
            return one(ROOM_FOR_USER, s -> {
                s.bindLong(1, userId);
                s.bindLong(2, roomId);
            }, r -> r.integer(1));
        }

        @Override
        public Long firstRoomIdForUser(long userId) {
            return one(FIRST_ROOM_FOR_USER, s -> s.bindLong(1, userId), r -> r.integer(1));
        }

        @Override
        public String accountJoinCode() {
            return one(FIRST_ACCOUNT, s -> {}, r -> r.text(3));
        }

        @Override
        public LayoutAccount layoutAccount() {
            return one(FIRST_ACCOUNT, s -> {}, r -> new LayoutAccount(r.integer(1), r.text(4), ts(r.text(8))));
        }

        @Override
        public Room room(long id) {
            return one(ROOM_BY_ID, s -> s.bindLong(1, id), Reader::readRoom);
        }

        @Override
        public List<User> roomUsers(long roomId) {
            return all(ROOM_USERS, s -> s.bindLong(1, roomId), Reader::readUser);
        }

        /**
         * {@code room.messages.last_page}: the 40 newest, oldest first, with the rest of a row read
         * only when {@code cached} has no fragment for that version.
         */
        @Override
        public <F> List<PageMessage<F>> lastPage(long roomId, Function<MessageKey, F> fragment) {
            List<PageMessage<F>> page = all(LAST_PAGE, s -> s.bindLong(1, roomId), r -> {
                MessageKey key = new MessageKey(r.integer(1), r.text(6));
                F found = fragment.apply(key);
                Message message = found != null ? null
                        : new Message(key.getId(), r.integer(2), r.integer(3), r.text(4), ts(r.text(5)), ts(key.getVersion()));
                return new PageMessage<>(key, found, message);
            });
            Collections.reverse(page);
            return page;
        }

        @Override
        public boolean paged(long roomId) {
            return one(PAGED, s -> s.bindLong(1, roomId), r -> Boolean.TRUE) != null;
        }

        @Override
        public String richTextBody(String recordType, long recordId, String name) {
            return one(RICH_TEXT_BODY, s -> {
                s.bindLong(1, recordId);
                s.bindText(2, recordType);
                s.bindText(3, name);
            }, r -> r.text(3));
        }

        @Override
        public boolean hasAttachedBlob(String recordType, long recordId, String name) {
            return one(ATTACHED_BLOB, s -> {
                s.bindText(1, recordType);
                s.bindLong(2, recordId);
                s.bindText(3, name);
            }, r -> Boolean.TRUE) != null;
        }

        @Override
        public List<Boost> boosts(long messageId) {
            return all(BOOSTS_FOR_MESSAGE, s -> s.bindLong(1, messageId),
                    r -> new Boost(r.integer(1), r.integer(2), r.integer(3), r.text(4), ts(r.text(6))));
        }

        private static User readUser(Sqlite.Statement r) {
            return new User(r.integer(1), r.text(2), (int) r.integer(5), r.text(7), ts(r.text(10)));
        }

        private static Room readRoom(Sqlite.Statement r) {
            return new Room(r.integer(1), r.text(2), RoomKind.Companion.of(r.text(3)), r.integer(4), ts(r.text(5)), ts(r.text(6)));
        }
    }

    static Ts ts(String text) {
        return Ts.Companion.parse(text);
    }
}
