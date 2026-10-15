package io.quarkus.campfire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import io.netty.util.IllegalReferenceCountException;

import campfire.kit.Action;
import campfire.kit.Body;
import campfire.kit.ChunkedBody;
import campfire.kit.Ctx;
import campfire.kit.Headers;
import campfire.kit.Middleware;
import campfire.kit.Response;
import campfire.views.FragmentCache;
import kotlin.Pair;

/**
 * Private, authenticated whole responses ({@code crates/campfire/src/response_cache.rs}, the
 * Kotlin port's {@code ResponseCache}), kept in Caffeine.
 *
 * <p>Authentication and the room's access check run before every lookup. A read-only SQLite
 * connection of its own observes commits from every writer ({@code PRAGMA data_version}); its
 * generation is captured before authentication ({@link Snapshot}) and checked again at lookup and
 * admission, so a commit during authentication or rendering can't populate the new generation with
 * an old authorization snapshot. Only the completed, bounded identity or gzip representation is
 * kept ({@link #completed}), for at most 15 s.
 */
public final class ResponseCache {
    /** The largest body admitted ({@code MAX_BODY}). */
    static final int MAX_BODY = 1024 * 1024;
    /** The most key material hashed before a request is left uncached ({@code MAX_KEY_INPUT}). */
    private static final int MAX_KEY_INPUT = 8192;
    /** How long an entry lives without a commit ({@code TTL}). */
    private static final Duration TTL = Duration.ofSeconds(15);
    private static final Set<String> ENDPOINTS = Set.of("rooms#show", "messages#index", "users/sidebars#show", "searches#index");
    private static final HexFormat HEX = HexFormat.of();
    /** The response headers each request gets afresh, dropped from what's kept. */
    private static final List<String> FRESH_HEADERS = List.of("set-cookie", "date", "x-request-id", "x-runtime");
    /** Caffeine's maintenance runs on the calling thread instead of waking a ForkJoinPool thread. */
    private static final java.util.concurrent.Executor CALLER = Runnable::run;

    private ResponseCache() {
    }

    /**
     * A completed response as kept: status, headers less the fresh ones, its one body buffer, and the
     * forms the HTTP layer writes ({@link Hit}): the same bytes in a Netty direct buffer, and each
     * header value encoded, both made once when kept. The entry owns one reference to {@code direct};
     * the cache releases it when the entry goes (expired, replaced or evicted), and each write holds
     * its own, so the memory is freed once the last write is done.
     */
    record Cached(int status, Headers headers, byte[] body, ByteBuf direct, Map<String, AsciiString> values) {
        /** Both copies of the body count against the cache's limit. */
        int weight() {
            return 2 * body.length + 64 * headers.getSize();
        }
    }

    /**
     * On a response served from the cache, what its entry already holds in written form: the body
     * ({@code source}) in the entry's direct buffer, and the entry's header values encoded. The HTTP
     * layer ({@link HttpRoutes}) writes the buffer as is while the body is still that very array (no
     * copy into a direct buffer, no allocation for one) and uses an encoded value for any header whose
     * value is one of the entry's (a hit's own headers, such as cookies, are encoded per response).
     */
    record Hit(byte[] source, ByteBuf page, Map<String, AsciiString> values) {
        /** A duplicate of the page holding its own reference, or null when the entry has already released it. */
        ByteBuf retainedPage() {
            try {
                return page.retainedDuplicate();
            } catch (IllegalReferenceCountException e) {
                return null;
            }
        }
    }

    /** {@code PRAGMA data_version}'s current value ({@link Db#dataVersion}); throws when SQLite can't answer. */
    @FunctionalInterface
    interface DataVersion {
        long read();
    }

    /** The completed-response store and its database observer ({@code Store}). */
    public static final class Store {
        private final DataVersion observer;
        private final Cache<Key, Cached> responses;
        private long dataVersion;
        private long generation;
        final boolean enabled;
        /** The SQLite stage's thread, the only one that observes; other threads ask it ({@code forward}). */
        private volatile Thread owner;
        private Supplier<Long> forward;

        Store(DataVersion observer, long capacity) {
            this.observer = observer;
            enabled = capacity != 0;
            responses = Caffeine.newBuilder().maximumWeight(Math.max(capacity, 0)).weigher((Key key, Cached value) -> value.weight())
                    .expireAfterWrite(TTL).executor(CALLER)
                    .removalListener((Key key, Cached value, RemovalCause cause) -> {
                        if (value != null) value.direct().release();
                    }).build();
            dataVersion = observer.read();
        }

        /** The generation as last observed (the SQLite stage's thread only: its statistics). */
        long observedGeneration() {
            return generation;
        }

        void attach(Thread owner, Supplier<Long> forward) {
            this.forward = forward;
            this.owner = owner;
        }

        /**
         * The current database generation, or null when SQLite can't say (both caches are then
         * bypassed). Only the SQLite stage thread observes it: event loops use the generations it
         * read for their request ({@link SqliteStage.Prefetched}) or send the request on; other
         * threads ask the stage and wait.
         */
        Long version() {
            if (io.vertx.core.Context.isOnEventLoopThread()) Staged.needStage();
            Thread o = owner;
            if (o != null && o != Thread.currentThread()) return forward.get();
            try {
                long current = observer.read();
                if (current != dataVersion) {
                    generation++;
                    dataVersion = current;
                }
                return generation;
            } catch (IllegalStateException e) {
                return null;
            }
        }

        /** The response kept for {@code ticket}, if its generation is still current. */
        Cached get(Ticket ticket) {
            SqliteStage.Prefetched p = Staged.current();
            Long current = p != null ? p.generationAfter : version();
            if (current == null || current != ticket.generation) return null;
            Cached cached = responses.getIfPresent(ticket.key);
            return cached;
        }

        /** Keeps {@code response} unless a commit has moved the generation on since the ticket's. */
        /** Keeps the response unless a commit has moved the generation on since the ticket's. */
        void put(Ticket ticket, int status, Headers headers, byte[] body) {
            Long current = version();
            if (current == null || current != ticket.generation) return;
            Map<String, AsciiString> values = new HashMap<>();
            for (Pair<? extends String, ? extends String> header : headers) values.computeIfAbsent(header.getSecond(), HttpRoutes::ascii);
            ByteBuf direct = Unpooled.directBuffer(body.length, body.length).writeBytes(body);
            responses.put(ticket.key, new Cached(status, headers, body, direct, Map.copyOf(values)));
        }
    }

    /**
     * The references the request being handled borrows ({@link HttpRoutes}): every fragment its
     * view hands out is held until its response is written.
     */
    static final ScopedValue<campfire.kit.Borrows> BORROWS = ScopedValue.newInstance();

    /** What a request captured before authentication: the generation, and its fragment store's view. */
    record Snapshot(Long generation, FragmentCache fragments) {
        static Snapshot capture(Ctx c) {
            CampfireApp app = CampfireApp.of(c);
            SqliteStage.Prefetched p = Staged.current();
            Long generation = p != null ? p.generationBefore : app.responseCache.version();
            FragmentCache fragments = generation != null ? app.fragments.namespace(generation).scoped(origin(c.urlFor(""))) : new FragmentCache(0);
            if (BORROWS.isBound()) fragments = fragments.borrowing(BORROWS.get());
            return new Snapshot(generation, fragments);
        }
    }

    /** A request {@link #begin} found cacheable ({@code Round}). */
    record Round(long generation, String endpoint) {
    }

    /** A lookup that missed: where its response may be kept, if it completes ({@code Ticket}). */
    record Ticket(Store store, long generation, Key key) {
    }

    /** The last origin hashed and its digest, since nearly every request comes from the same one. */
    private static volatile Pair<String, String> lastOrigin = new Pair<>("", "");

    private static String origin(String base) {
        Pair<String, String> last = lastOrigin;
        if (last.getFirst().equals(base)) return last.getSecond();
        String digest = HEX.formatHex(campfire.kit.PagePartsKt.sha256(base.getBytes(StandardCharsets.UTF_8)));
        lastOrigin = new Pair<>(base, digest);
        return digest;
    }

    /** The request's pre-authentication fragment view, captured now if routing didn't. */
    static FragmentCache fragments(Ctx c) {
        Snapshot snapshot = Current.get(c, Snapshot.class);
        return (snapshot != null ? snapshot : Snapshot.capture(c)).fragments();
    }

    /** {@code dispatch_with_fragment_cache}: {@code router} with the request's snapshot captured first. */
    static Action snapshotting(Action router) {
        return c -> {
            Current.set(c, Snapshot.class, Snapshot.capture(c));
            return router.call(c);
        };
    }

    /** {@code action} as {@code controllers::dispatch} runs it for {@code endpoint}: {@link #begin}, then {@link #prepare}. */
    static Action around(String endpoint, Action action) {
        return c -> {
            begin(c, endpoint);
            Response response = action.call(c);
            prepare(c, response);
            return response;
        };
    }

    static void begin(Ctx c, String endpoint) {
        if (!ENDPOINTS.contains(endpoint) || !eligibleRequest(c)) return;
        Snapshot snapshot = Current.get(c, Snapshot.class);
        if (snapshot == null || snapshot.generation() == null) return;
        if (CampfireApp.of(c).responseCache.enabled) Current.set(c, Round.class, new Round(snapshot.generation(), endpoint));
    }

    /**
     * {@code lookup}: after the action's fresh authentication, permission checks and request side
     * effects, the kept response for this request, or null. A miss leaves a {@link Ticket}.
     */
    static Response lookup(Ctx c) {
        Round round = Current.get(c, Round.class);
        if (round == null) return null;
        if (Concerns.authenticatedBy(c) != Concerns.AuthenticatedBy.SESSION || !c.flash().isEmpty()) return null;
        Concerns.CurrentSession session = Current.get(c, Concerns.CurrentSession.class);
        if (session == null) return null;
        var user = Concerns.currentUser(c);
        if (user == null) return null;
        Store store = CampfireApp.of(c).responseCache;
        Key key = requestKey(c, round, user.getId(), session.session().getId());
        if (key == null) return null;
        Ticket ticket = new Ticket(store, round.generation(), key);
        Cached cached = store.get(ticket);
        if (cached != null) {
            Response hit = new Response(cached.status(), cached.headers().copy(), new Body.Bytes(cached.body()));
            hit.getExtensions().put(Hit.class, new Hit(cached.body(), cached.direct(), cached.values()));
            return hit;
        }
        Current.set(c, Ticket.class, ticket);
        return null;
    }

    /** {@code prepare}: marks a GET's 200 response for {@link #completed} to keep. */
    static void prepare(Ctx c, Response response) {
        Ticket ticket = Current.get(c, Ticket.class);
        if (ticket == null) return;
        if (!"GET".equals(c.getRequest().getMethod()) || response.getStatus() != 200 || !c.flash().isEmpty()) return;
        long size;
        Body body = response.getBody();
        if (body instanceof Body.Bytes bytes) {
            size = bytes.getBytes().length;
        } else if (body instanceof Body.Page page) {
            size = page.getParts().getLength();
        } else {
            return;
        }
        if (size <= MAX_BODY && cacheableHeaders(response.getHeaders())) response.getExtensions().put(Ticket.class, ticket);
    }

    /**
     * {@code completed}, outside the kit and {@code Rack::Deflater}: keeps exactly the finished
     * representation of a marked response, collected into one buffer and sent with a
     * {@code Content-Length}, as Rust's collected body is.
     */
    static final Middleware completed = (request, next) -> {
        Response response = next.handle(request);
        Ticket ticket = (Ticket) response.getExtensions().remove(Ticket.class);
        if (ticket == null || response.getStatus() != 200 || !cacheableHeaders(response.getHeaders())) return response;
        byte[] bytes = collect(response.getBody());
        response.getHeaders().set("content-length", Integer.toString(bytes.length));
        response.setBody(new Body.Bytes(bytes));
        response.getExtensions().remove(ChunkedBody.class);
        Headers headers = response.getHeaders().copy();
        for (String name : FRESH_HEADERS) headers.remove(name);
        ticket.store().put(ticket, response.getStatus(), headers, bytes);
        return response;
    };

    private static byte[] collect(Body body) {
        if (body instanceof Body.Bytes bytes) return bytes.getBytes();
        List<byte[]> chunks = body.chunks();
        int length = 0;
        for (byte[] chunk : chunks) length += chunk.length;
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, at, chunk.length);
            at += chunk.length;
        }
        return out;
    }

    /** {@code eligible_request}: a plain GET or HEAD, with no body, range, upgrade or cache bypass. */
    private static boolean eligibleRequest(Ctx c) {
        var request = c.getRequest();
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) return false;
        if (!method.equals(request.getOriginalMethod()) || request.getRawPost().length != 0) return false;
        Headers headers = request.getHeaders();
        if (headers.contains("range") || headers.contains("upgrade") || directives(headers, NO_CACHE_OR_STORE)) return false;
        String pragma = request.header("pragma");
        return pragma == null || !pragma.equalsIgnoreCase("no-cache");
    }

    private static final List<String> NO_CACHE_OR_STORE = List.of("no-cache", "no-store");
    private static final List<String> NO_STORE_OR_TRANSFORM = List.of("no-store", "no-transform");

    private static boolean cacheableHeaders(Headers headers) {
        return !directives(headers, NO_STORE_OR_TRANSFORM);
    }

    /** Whether any visible-ASCII {@code Cache-Control} value has a directive named one of {@code rejected}. */
    private static boolean directives(Headers headers, List<String> rejected) {
        for (String value : headers.all("cache-control")) {
            if (!visible(value)) continue;
            for (String directive : value.split(",", -1)) {
                String trimmed = directive.trim();
                int eq = trimmed.indexOf('=');
                String name = eq < 0 ? trimmed : trimmed.substring(0, eq);
                for (String r : rejected) if (name.equalsIgnoreCase(r)) return true;
            }
        }
        return false;
    }

    private static boolean visible(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\t' && (ch < ' ' || ch > '~')) return false;
        }
        return true;
    }

    /**
     * {@code request_key}: the SHA-256 of length-prefixed fields: generation, endpoint, user,
     * session, request target and origin, then every request header (sorted by name, values in
     * order) except validators and request tracing. Null past {@link #MAX_KEY_INPUT} bytes.
     */
    private static Key requestKey(Ctx c, Round round, long user, long session) {
        return requestKey(round.generation(), round.endpoint(), user, session, c.getRequest().getRequest().getTarget(), c.urlFor(""), c.getRequest().getHeaders());
    }

    static Key requestKey(long generation, String endpoint, long user, long session, String target, String origin, Headers headers) {
        return KeyWriter.PER_THREAD.use(writer -> requestKey(writer.begin(), generation, endpoint, user, session, target, origin, headers));
    }

    private static Key requestKey(KeyWriter key, long generation, String endpoint, long user, long session, String target, String origin, Headers headers) {
        key.number(generation);
        key.utf8(endpoint);
        key.number(user);
        key.number(session);
        key.latin1(target);
        key.utf8(origin);
        String[] names = key.sortedNames(headers);
        for (int i = 0, n = key.names; i < n; i++) {
            String name = names[i];
            if (name.equals("if-none-match") || name.equals("if-modified-since") || name.equals("x-request-id") || name.equals("x-request-start")) continue;
            key.latin1(name);
            for (Pair<? extends String, ? extends String> header : headers) {
                if (header.getFirst().equals(name)) key.latin1(header.getSecond());
            }
            key.empty();
        }
        return key.finish();
    }

    /** A request key: the 32 bytes of its SHA-256 (no hex string). */
    record Key(long a, long b, long c, long d) {
    }

    /**
     * Length-prefixed (little-endian u64) fields into a SHA-256, as {@code String.getBytes} would
     * feed them, without allocating per request: one writer per event loop (pooled on virtual threads), its digest and scratch reused.
     */
    private static final class KeyWriter {
        /** One per event loop; on a virtual thread one borrowed for the request key and given back (the Kotlin port's fork, PerThread). */
        static final campfire.rails.PerThread<KeyWriter> PER_THREAD = new campfire.rails.PerThread<>(KeyWriter::new);
        private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

        private final MessageDigest digest;
        private byte[] scratch = new byte[256];
        private String[] sorted = new String[32];
        private int length;
        int names;

        private KeyWriter() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Starts a key, whatever a halted one left behind. */
        KeyWriter begin() {
            digest.reset();
            length = 0;
            return this;
        }

        void number(long value) {
            LONG.set(scratch, 0, value);
            field(scratch, 8);
        }

        void empty() {
            field(scratch, 0);
        }

        /** {@code value.getBytes(ISO_8859_1)}, encoded into the scratch when every char fits a byte. */
        void latin1(String value) {
            int n = value.length();
            byte[] bytes = room(n);
            for (int i = 0; i < n; i++) {
                char ch = value.charAt(i);
                if (ch > 0xFF) {
                    byte[] encoded = value.getBytes(StandardCharsets.ISO_8859_1);
                    field(encoded, encoded.length);
                    return;
                }
                bytes[i] = (byte) ch;
            }
            field(bytes, n);
        }

        /** {@code value.getBytes(UTF_8)}, encoded into the scratch when it's all ASCII. */
        void utf8(String value) {
            int n = value.length();
            byte[] bytes = room(n);
            for (int i = 0; i < n; i++) {
                char ch = value.charAt(i);
                if (ch > 0x7F) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    field(encoded, encoded.length);
                    return;
                }
                bytes[i] = (byte) ch;
            }
            field(bytes, n);
        }

        /** The headers' distinct names, sorted, in a reused array ({@link #names} of them). */
        String[] sortedNames(Headers headers) {
            int n = 0;
            for (Pair<? extends String, ? extends String> header : headers) {
                String name = header.getFirst();
                int at = n;
                while (at > 0 && sorted[at - 1].compareTo(name) > 0) at--;
                if (at > 0 && sorted[at - 1].equals(name)) continue;
                if (n == sorted.length) sorted = Arrays.copyOf(sorted, n * 2);
                System.arraycopy(sorted, at, sorted, at + 1, n - at);
                sorted[at] = name;
                n++;
            }
            names = n;
            return sorted;
        }

        /** The key, or null past {@link #MAX_KEY_INPUT}; the writer is reset either way. */
        Key finish() {
            Arrays.fill(sorted, 0, names, null);
            if (length > MAX_KEY_INPUT) return null;
            try {
                digest.digest(scratch, 0, 32);
            } catch (DigestException e) {
                throw new IllegalStateException(e);
            }
            return new Key((long) LONG.get(scratch, 0), (long) LONG.get(scratch, 8), (long) LONG.get(scratch, 16), (long) LONG.get(scratch, 24));
        }

        private byte[] room(int n) {
            if (n + 8 > scratch.length) scratch = new byte[Math.max(n + 8, scratch.length * 2)];
            return scratch;
        }

        /** The 8-byte length, then the bytes: the prefix goes in the scratch's tail, the bytes are at its head. */
        private void field(byte[] bytes, int n) {
            length += n + 8;
            byte[] prefix = room(n);
            int at = prefix.length - 8;
            LONG.set(prefix, at, (long) n);
            digest.update(prefix, at, 8);
            digest.update(bytes, 0, n);
        }
    }
}
