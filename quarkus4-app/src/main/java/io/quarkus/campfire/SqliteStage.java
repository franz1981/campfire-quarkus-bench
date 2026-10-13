package io.quarkus.campfire;

import java.util.ArrayList;
import java.util.List;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import campfire.db.Room;
import campfire.db.Session;
import campfire.db.User;
import io.quarkus.runtime.Startup;
import io.vertx.core.Context;

import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;

/**
 * The only thread that calls SQLite. SQLite serializes its readers on a process-wide WAL-index
 * mutex, so instead of several threads contending for it, requests queue here and are served in
 * batches:
 *
 * <ol>
 * <li>the generation is observed ({@code PRAGMA data_version});</li>
 * <li>in one read transaction (one snapshot), each request's session, user and room membership are
 * read;</li>
 * <li>the generation is observed again.</li>
 * </ol>
 *
 * A request gets its reads and both generations ({@link Prefetched}): the first was observed before
 * its authentication reads and the second after them, as the response cache requires. Anything else
 * that needs SQLite (a miss's render, the hourly session refresh) runs here as a {@link #run job}.
 */
@Startup
@Singleton
public class SqliteStage {
    private static final Logger LOG = Logger.getLogger(SqliteStage.class);

    /** The reads a request's authentication needs, and the generations observed around them. */
    public static final class Prefetched {
        final String token;
        /** The signed cookie value {@link #token} was verified from, so the kit needn't verify it again. */
        final String rawCookie;
        final long roomId;
        Session session;
        User user;
        Room room;
        Long generationBefore;
        Long generationAfter;
        /** The batch's reads failed: the request runs on the stage instead, with fresh reads. */
        boolean failed;
        /** Set when the request turned out to need SQLite beyond these reads, on an event loop. */
        boolean needsStage;

        Prefetched(String token, String rawCookie, long roomId) {
            this.token = token;
            this.rawCookie = rawCookie;
            this.roomId = roomId;
        }
    }

    private record Lookup(Prefetched prefetched, Context context, Consumer<Prefetched> done) {
    }

    /** The most tasks taken into one batch. */
    private static final int MAX_BATCH = 1024;
    private static final VarHandle WAITER;

    static {
        try {
            WAITER = MethodHandles.lookup().findVarHandle(SqliteStage.class, "waiter", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Tasks from the event loops (producers) to this thread (the only consumer): an unbounded
     * multi-producer queue whose producers claim a slot with one atomic add (JCTools'
     * {@code MpscUnboundedXaddArrayQueue}), bounded in practice by the open connections (HTTP/1.1:
     * one request in flight per connection).
     */
    private final MessagePassingQueue<Object> queue = new MpscUnboundedXaddArrayQueue<>(1024, 2);
    /**
     * This thread while it is about to park, null otherwise. A producer offers, then unparks the
     * waiter it finds (one producer per park, by CAS); the consumer advertises itself, then re-checks
     * the queue before parking. Both sides order their write before their read (the producer's
     * offer is an atomic add, the consumer's advertisement a volatile write), so either the consumer
     * sees the task or the producer sees the waiter: no wakeup is missed.
     */
    private volatile Thread waiter;
    private volatile boolean running = true;
    private Thread thread;

    @Inject
    Db db;

    @Inject
    CampfireApp app;

    @PostConstruct
    void start() {
        thread = Thread.ofPlatform().name("campfire-sqlite").start(this::loop);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        LockSupport.unpark(thread);
        thread.join(5000);
    }

    /** Reads {@code token}'s session, user and membership of {@code roomId}; {@code done} then runs on {@code context} (the request's). */
    void lookup(String token, String rawCookie, long roomId, Context context, Consumer<Prefetched> done) {
        submit(new Lookup(new Prefetched(token, rawCookie, roomId), context, done));
    }

    /** Runs {@code job} on this thread, after the lookups queued before it. */
    void run(Runnable job) {
        submit(job);
    }

    private void submit(Object task) {
        queue.offer(task);
        Thread parked = waiter;
        if (parked != null && WAITER.compareAndSet(this, parked, null)) LockSupport.unpark(parked);
    }

    private void loop() {
        List<Object> batch = new ArrayList<>();
        MessagePassingQueue.Consumer<Object> take = batch::add;
        Thread self = Thread.currentThread();
        try {
            while (running) {
                if (queue.drain(take, MAX_BATCH) > 0) {
                    serve(batch);
                    batch.clear();
                    continue;
                }
                waiter = self;
                if (!queue.isEmpty() || !running) {
                    waiter = null;
                    continue;
                }
                LockSupport.park(this);
                waiter = null;
            }
        } finally {
            db.close();
        }
    }

    /** One batch: the lookups' reads in one transaction between two generation reads, then the jobs. Every task is answered. */
    private void serve(List<Object> batch) {
        List<Lookup> lookups = new ArrayList<>();
        for (Object task : batch) if (task instanceof Lookup lookup) lookups.add(lookup);
        if (!lookups.isEmpty()) {
            try {
                ResponseCache.Store store = app.responseCache;
                Long before = store.version();
                db.inReadTransaction(r -> {
                    for (Lookup lookup : lookups) read(r, lookup.prefetched());
                });
                Long after = store.version();
                for (Lookup lookup : lookups) {
                    lookup.prefetched().generationBefore = before;
                    lookup.prefetched().generationAfter = after;
                }
            } catch (Throwable t) {
                LOG.error("SQLite stage reads failed", t);
                for (Lookup lookup : lookups) lookup.prefetched().failed = true;
            }
            for (Lookup lookup : lookups) {
                try {
                    lookup.context().runOnContext(ignored -> lookup.done().accept(lookup.prefetched()));
                } catch (Throwable t) {
                    LOG.error("SQLite stage delivery failed", t);
                }
            }
        }
        for (Object task : batch) {
            if (task instanceof Runnable job) {
                try {
                    job.run();
                } catch (Throwable t) {
                    LOG.error("SQLite stage job failed", t);
                }
            }
        }
    }

    private static void read(Reads r, Prefetched p) {
        p.session = r.sessionByToken(p.token);
        if (p.session == null) return;
        p.user = r.user(p.session.getUserId());
        if (p.user == null) return;
        p.room = r.roomForUser(p.user.getId(), p.roomId);
    }
}
