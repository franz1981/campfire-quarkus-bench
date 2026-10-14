package io.quarkus.campfire;

import java.util.ArrayList;
import java.util.List;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import campfire.db.Room;
import campfire.db.Session;
import campfire.db.User;
import io.quarkus.runtime.Startup;
import io.vertx.core.Context;

import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;

/**
 * The only thread that calls SQLite, and it does nothing else. SQLite serializes its readers on a
 * process-wide WAL-index mutex, so instead of several threads contending for it, every SQL call
 * queues here and is served in arrival order:
 *
 * <ul>
 * <li>reads (a cache-eligible request's authentication {@link #lookup}, or any {@link #read} a
 * request pool thread forwards) run in read transactions, each a sub-batch bounded by count and by
 * time, the generation ({@code PRAGMA data_version}) observed before and after it;</li>
 * <li>a {@link #version} observation or a {@link #write} runs between sub-batches, outside any read
 * transaction.</li>
 * </ul>
 *
 * A lookup gets its reads and both generations ({@link Prefetched}): the first was observed before
 * its authentication reads and the second after them, as the response cache requires. A failing read
 * fails only its own task. Rendering, gzip and everything else run elsewhere (event loops, the
 * {@link RequestPool}).
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

    private record Lookup(Prefetched prefetched, Context context, Consumer<Prefetched> done, long submitted, java.util.concurrent.atomic.AtomicBoolean answered) {
    }

    /** A read forwarded by a thread that blocks for its result. */
    private record ReadOp(Function<Reads, Object> work, CompletableFuture<Object> result, long submitted) {
    }

    /** A generation observation forwarded by a thread that blocks for it. */
    private record VersionOp(CompletableFuture<Long> result, long submitted) {
    }

    /** A write forwarded by a thread that blocks until it is done. */
    private record WriteOp(Runnable work, CompletableFuture<Object> result, long submitted) {
    }

    /** The most tasks taken from the queue at once. */
    private static final int MAX_BATCH = 1024;

    /** The most reads in one read transaction. */
    @ConfigProperty(name = "campfire.sqlite.max-reads-per-transaction", defaultValue = "256")
    int maxReadsPerTransaction;

    /** The longest a read transaction stays open once its first read ran (short transactions let WAL checkpoints finish). */
    @ConfigProperty(name = "campfire.sqlite.max-transaction-micros", defaultValue = "2000")
    long maxTransactionMicros;

    /** Seconds between two statistics lines in the log; 0: none. */
    @ConfigProperty(name = "campfire.sqlite.stats-seconds", defaultValue = "0")
    int statsSeconds;
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
        thread = Thread.ofPlatform().name("campfire-sqlite").unstarted(this::loop);
        db.attach(this, thread);
        app.responseCache.attach(thread, this::version);
        thread.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        LockSupport.unpark(thread);
        thread.join(5000);
        // whatever is still queued is answered: lookups as failed, forwarded work exceptionally; only once
        // this thread has ended (the queue has a single consumer)
        if (!thread.isAlive()) queue.drain(SqliteStage::abandon);
        else LOG.error("the SQLite stage didn't stop within 5 s; its queued tasks are left unanswered");
    }

    private static void abandon(Object task) {
        IllegalStateException stopped = new IllegalStateException("the SQLite stage has stopped");
        switch (task) {
            case Lookup lookup -> {
                lookup.prefetched().failed = true;
                deliver(lookup);
            }
            case ReadOp op -> op.result().completeExceptionally(stopped);
            case VersionOp op -> op.result().completeExceptionally(stopped);
            case WriteOp op -> op.result().completeExceptionally(stopped);
            default -> {
            }
        }
    }

    boolean isStageThread() {
        return Thread.currentThread() == thread;
    }

    /** Reads {@code token}'s session, user and membership of {@code roomId}; {@code done} then runs on {@code context} (the request's). */
    void lookup(String token, String rawCookie, long roomId, Context context, Consumer<Prefetched> done) {
        submit(new Lookup(new Prefetched(token, rawCookie, roomId), context, done, System.nanoTime(), new java.util.concurrent.atomic.AtomicBoolean()));
    }

    /**
     * Runs {@code work} with this thread's reads, inside a read transaction, and returns its result:
     * for a thread that may block (not an event loop, not this thread).
     */
    @SuppressWarnings("unchecked")
    <T> T read(Function<Reads, T> work) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        submit(new ReadOp((Function<Reads, Object>) work, result, blocking()));
        return (T) join(result);
    }

    /** The response cache's current generation, observed on this thread (null when SQLite can't say). */
    Long version() {
        CompletableFuture<Long> result = new CompletableFuture<>();
        submit(new VersionOp(result, blocking()));
        return join(result);
    }

    /** Runs {@code work} (a write) on this thread, outside any read transaction, and waits for it. */
    void write(Runnable work) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        submit(new WriteOp(work, result, blocking()));
        join(result);
    }

    /** The submission time; refuses the threads that must not wait for SQLite. */
    private long blocking() {
        if (Context.isOnEventLoopThread()) throw new IllegalStateException("waiting for SQLite on an event loop");
        if (isStageThread()) throw new IllegalStateException("the SQLite stage waiting for itself");
        return System.nanoTime();
    }

    private static <T> T join(CompletableFuture<T> result) {
        try {
            return result.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }

    private void submit(Object task) {
        if (!running) {  // stopped, or its thread died: answered now, never left waiting
            abandon(task);
            return;
        }
        queue.offer(task);
        Thread parked = waiter;
        if (parked != null && WAITER.compareAndSet(this, parked, null)) LockSupport.unpark(parked);
    }

    private void loop() {
        List<Object> batch = new ArrayList<>();
        MessagePassingQueue.Consumer<Object> take = batch::add;
        Thread self = Thread.currentThread();
        long nextStats = statsSeconds > 0 ? System.nanoTime() + TimeUnit.SECONDS.toNanos(statsSeconds) : Long.MAX_VALUE;
        try {
            while (running) {
                if (System.nanoTime() - nextStats >= 0) {
                    nextStats = System.nanoTime() + TimeUnit.SECONDS.toNanos(statsSeconds);
                    LOG.info(stats.drain(app.responseCache.observedGeneration()));
                }
                if (queue.drain(take, MAX_BATCH) > 0) {
                    try {
                        serve(batch);
                    } catch (Throwable t) {
                        // not expected (each task handles its own errors): every task of the batch is still answered
                        LOG.error("SQLite stage batch failed", t);
                        abortTransaction();
                        for (Object task : batch) abandon(task);
                    }
                    batch.clear();
                    continue;
                }
                waiter = self;
                if (!queue.isEmpty() || !running) {
                    waiter = null;
                    continue;
                }
                if (statsSeconds > 0) LockSupport.parkNanos(this, Math.max(1, nextStats - System.nanoTime()));
                else LockSupport.park(this);
                waiter = null;
            }
        } finally {
            running = false;  // from now on submit answers at once
            db.close();
        }
    }

    /** The read transaction being filled: its lookups (answered once it commits), size and start. */
    private final List<Lookup> lookups = new ArrayList<>();
    private boolean inTransaction;
    private int reads;
    private long transactionStart;
    private Long generationBefore;

    /** One drain of the queue, in arrival order. Every task is answered. */
    private void serve(List<Object> batch) {
        for (Object task : batch) {
            long now = System.nanoTime();
            switch (task) {
                case Lookup lookup -> {
                    stats.waited(now - lookup.submitted());
                    if (!openTransaction()) {
                        lookup.prefetched().failed = true;
                        deliver(lookup);
                        continue;
                    }
                    try {
                        read(db.reader(), lookup.prefetched());
                    } catch (Throwable t) {
                        LOG.error("SQLite stage reads failed", t);
                        lookup.prefetched().failed = true;
                    }
                    lookups.add(lookup);
                    stats.lookups++;
                    afterRead();
                }
                case ReadOp op -> {
                    stats.waited(now - op.submitted());
                    if (!openTransaction()) {
                        op.result().completeExceptionally(new IllegalStateException("SQLite read transaction failed to begin"));
                        continue;
                    }
                    try {
                        op.result().complete(op.work().apply(db.reader()));
                    } catch (Throwable t) {
                        op.result().completeExceptionally(t);
                    }
                    stats.readOps++;
                    afterRead();
                }
                case VersionOp op -> {
                    stats.waited(now - op.submitted());
                    closeTransaction();
                    op.result().complete(app.responseCache.version());
                    stats.versionOps++;
                }
                case WriteOp op -> {
                    stats.waited(now - op.submitted());
                    closeTransaction();
                    try {
                        op.work().run();
                        op.result().complete(null);
                    } catch (Throwable t) {
                        op.result().completeExceptionally(t);
                    }
                    stats.writeOps++;
                }
                default -> LOG.error("unknown SQLite stage task " + task);
            }
        }
        closeTransaction();
    }

    /** Opens a read transaction unless one is open, the generation observed first; false when it can't begin. */
    private boolean openTransaction() {
        if (inTransaction) return true;
        generationBefore = app.responseCache.version();
        try {
            db.reader().begin();
        } catch (Throwable t) {
            LOG.error("SQLite stage read transaction failed to begin", t);
            return false;
        }
        inTransaction = true;
        reads = 0;
        transactionStart = System.nanoTime();
        stats.transactions++;
        return true;
    }

    /** Ends the read transaction once it holds its most reads or has been open its longest. */
    private void afterRead() {
        reads++;
        if (reads >= maxReadsPerTransaction || System.nanoTime() - transactionStart >= TimeUnit.MICROSECONDS.toNanos(maxTransactionMicros)) closeTransaction();
    }

    /** Commits the open read transaction, observes the generation again, and answers its lookups. */
    private void closeTransaction() {
        if (!inTransaction) return;
        inTransaction = false;
        boolean committed = true;
        try {
            db.reader().commit();
        } catch (Throwable t) {
            LOG.error("SQLite stage read transaction failed to commit", t);
            committed = false;
            abortTransaction();
        }
        Long after = app.responseCache.version();
        for (Lookup lookup : lookups) {
            Prefetched p = lookup.prefetched();
            if (!committed) p.failed = true;
            p.generationBefore = generationBefore;
            p.generationAfter = after;
            deliver(lookup);
        }
        lookups.clear();
    }

    /** After a failure: the connection out of any transaction, the stage's lookups answered as failed. */
    private void abortTransaction() {
        inTransaction = false;
        try {
            db.reader().rollback();
        } catch (Throwable ignored) {
            // no transaction was open
        }
        for (Lookup lookup : lookups) {
            lookup.prefetched().failed = true;
            deliver(lookup);
        }
        lookups.clear();
    }

    private static void deliver(Lookup lookup) {
        if (!lookup.answered().compareAndSet(false, true)) return;
        try {
            lookup.context().runOnContext(ignored -> lookup.done().accept(lookup.prefetched()));
        } catch (Throwable t) {
            LOG.error("SQLite stage delivery failed", t);
        }
    }

    private final Stats stats = new Stats();

    /** What the stage did since the last statistics line (this thread only). */
    private static final class Stats {
        long lookups, readOps, versionOps, writeOps, transactions, tasks, waitedNanos, maxWaitNanos;
        long since = System.nanoTime();
        long generation;

        void waited(long nanos) {
            tasks++;
            waitedNanos += nanos;
            if (nanos > maxWaitNanos) maxWaitNanos = nanos;
        }

        String drain(long observedGeneration) {
            long now = System.nanoTime();
            double seconds = (now - since) / 1e9;
            String line = String.format("SQLite stage: %.0f generations/s, %.0f lookups/s, %.0f reads/s, %.0f versions/s, %.0f writes/s, %.0f transactions/s, wait avg %.0f us max %.0f us",
                    (observedGeneration - generation) / seconds, lookups / seconds, readOps / seconds, versionOps / seconds, writeOps / seconds, transactions / seconds,
                    tasks == 0 ? 0.0 : waitedNanos / 1e3 / tasks, maxWaitNanos / 1e3);
            lookups = readOps = versionOps = writeOps = transactions = tasks = waitedNanos = maxWaitNanos = 0;
            generation = observedGeneration;
            since = now;
            return line;
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
