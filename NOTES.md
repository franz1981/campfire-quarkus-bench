# Notes: found, measured, deliberately not changed

The measurements here were taken during the investigation with diagnostic scripts that preceded
`bench.sh` and are not published (they were tied to the machine they ran on): per-thread CPU from
`schedstat`, async-profiler (`cpu`, `alloc`, `wall`, `lock`, `nativemem`), `perf trace` and
`perf stat` for syscalls, bpftrace uprobes on SQLite, JFR, `nstat` for the listen queue.

Every app produces the same output (parity 0 different, every loadgen response validated byte for
byte) with the same per-request correctness. Since the staged architecture, Quarkus does not issue
the same statements as Rust and Kotlin; what differs is listed under "Staged architecture".

## Staged architecture: what differs from Rust and Kotlin

- **One thread calls SQLite**, with one reader connection (Rust and Kotlin: 5 readers, several
  threads). Requests queue to it and are served in batches.
- **Per batch, not per request:** two `PRAGMA data_version` reads (before and after the batch's
  auth reads) and one read transaction around them. Each request still gets a generation observed
  before its auth reads and one after, and its own session, user and room-membership reads.
- **Read sharing** (identical auth reads in a batch run once) exists only as a measured experiment:
  it only pays off because the benchmark's clients share one session, so it is not used.
- **The session cookie is verified once**, when the request is staged; its expiry (20 years out) is
  evaluated then, about a millisecond before the kit would have.
- **A request that needs more than its prefetched reads** (a cache miss, the hourly session refresh,
  any other route) runs again from a fresh copy on the SQLite thread; the halted first attempt has
  no side effects (no write happens, its response and request-log line are dropped).

## Staged design: tried, no gain (2 cores, 3 event loops, 1 JVM, 3 windows, 2 interleaved reps)

- **One event-loop task per loop and batch** (instead of one `runOnContext` per request): 40.8–44.4k
  vs 41.0–45.4k req/s at 16 connections, 54.5–56.2k vs 53.6–56.7k at 1024. Eventfd `write`s on the
  SQLite thread (the loops' wakeups) stayed at 0.22–0.23 per request at 16 and 0.00 at 1024, where
  the loops never block (`epoll_pwait2` 0.01 per request). Not kept.

## Scalability bottleneck of the staged design: the one SQLite thread

At 4 cores Quarkus stops at about 100k req/s with about 3 of 4 cores used. At 1024 connections the
SQLite thread is on CPU 82%, runnable but waiting 13%, parked 5% (schedstat): it is the limit, and
its cost per request (9.1 µs with sqlite-jdbc) sets the ceiling. Cheaper calls raise it: the patched
driver (fewer JNI calls) +31%, FFM +37% (RESULTS.md), which is why the app now calls SQLite through
FFM only. Not done: more than one SQLite thread (each its own reader connection and `data_version`
observer, one shared generation), and backpressure between the event loops and the SQLite thread
(its queue is unbounded).

## Netty's LOW_MEM mode and the page write (measured, async-profiler `nativemem`)

With `-Xmx512m`, `Runtime.maxMemory()` is 477,626,368, so Netty 4.2.18's `AdaptivePoolingAllocator`
runs in LOW_MEM mode (`maxMemory() <= 512 MiB`). The epoll transport copies every heap buffer into a
direct one before writing (`AbstractEpollStreamChannel.filterOutboundMessage`,
`UnixChannelUtil.isBufferCopyNeededForWrite`). The page is about 24 KB, above the largest size class
(16,896 bytes), and in LOW_MEM the large-buffer magazines are skipped (`else if (!IS_LOW_MEM)`), so
each copy went to `allocateFallback`: a one-off chunk, one `malloc` and one `free` per response
(0.99 native allocations and 23,991 bytes per request on the event loops).

- `-Dio.netty.allocator.lowMemory=false` (in `bench.sh`): 0.001 allocations per request.
  It also turns on thread-local magazines for the event loops and allows 8 MiB chunks.
- The cached page is now also kept in native memory (`ResponseCache.DirectBody`, a read-only direct
  `ByteBuffer` made when the entry is stored), and a hit is written from it: no copy, no allocator.
  With LOW_MEM forced back on (`-Dio.netty.allocator.lowMemory=true`, checked in the running JVM's
  system properties), the event loops allocate 0.002 times per request. The cache's weight counts
  both copies of a body.

## The stopped reactive variant (`quarkus4-reactive-app/RESEARCH.md`, not built further)

A sub-agent researched expressing the batching with Mutiny, Vert.x, Quarkus or jOOQ: none forms
batches of "whatever is queued now" on a dedicated thread (details and source references there).
Two of its ideas are not applied yet: one joined statement per request (session, user and
membership on unique keys, so no read sharing), and several SQLite threads with one generation whose
observations are serialized.

## Backlog: an idiomatic reactive variant (separate experiment)

Goal: replace as much of the non-idiomatic code as possible with idiomatic Quarkus and Mutiny, and
match the current app's performance (context propagation off). Parity must stay 0 different. Known
so far:
- **Request flow as `Uni`**: the SQLite thread completes each request's emitter on that request's
  own context (`runOnContext`), so the rest of the request continues on its event loop; misses and
  session refreshes become `Uni<Response>` jobs on the same thread.
- **No ThreadLocal**: the kit's `Request` has its own extensions (`getExtensions`/`putExtension`)
  to carry a request's prefetched reads, and Quarkus runs each request on a duplicated Vert.x
  context whose locals are request-scoped.
- **Backpressure without blocking the producer**: a bounded intake split into credits per event
  loop; a loop without credit keeps the task in its own deque (no lock) and pauses the waiting
  connections; credits come back with the results. HTTP/1.1 already holds one request per
  connection in flight (`Http1ServerConnection`).
- **Batching stays custom**: no Mutiny, Vert.x, Quarkus or jOOQ facility closes a batch on "nothing
  more queued" (`quarkus4-reactive-app/RESEARCH.md`).
- **Scaling the same way**: N SQLite threads, each with its own connection and its own event loops
  (as TechEmpower's Quarkus pgclient keeps one connection per event loop), with the generation's
  observations serialized.

## Memory: why Quarkus' RSS was high, what fixed it, what is left

All on JDK 27 ParallelGC with the UBI options, `-Xmx512m` (measured with `-Xlog:gc*,gc+ergo*=debug`,
NMT, `jcmd System.map`, async-profiler `alloc`/`live`/`nativemem`; scripts in `work/` and
`scripts/profile-threads.sh`).

- **Eden is sized from the allocation rate.** JDK 27's ParallelGC grows eden whenever minor GCs come
  closer than `MinGCDistanceSecond` (0.1 s): `Adaptive: gc-distance (predicted vs goal): 0.035 vs
  0.100` (`psAdaptiveSizePolicy.cpp`, `compute_desired_eden_size`). So eden settles near allocation
  per second × 0.1 s: bytes per request × requests per second. Per request Quarkus allocated about
  as much as Kotlin (19.0 vs 19.8 KB at 2 cores), but served twice the requests.
- **At 4 cores eden then got stuck at its maximum.** Under 16 connections the young generation hit
  `MaxNewSize` (170 MB of 512); the policy trimmed the survivors (`Trim survivor under MaxNewSize
  pressure`), the tenuring threshold fell to 1, so the objects of the request in flight at each GC
  were promoted (~8 KB per GC), and eden can only shrink while promotion is under 1 KB per GC
  (`promoted_bytes_estimate() < 1*K`): `eden unchanged` forever, 171 MB at 1 connection vs Kotlin's
  42. Not a leak: the live-object profile holds only request-scoped objects, and dead bytes in the old
  gen are the same for both apps (3.4 vs 2.9 MB). The UBI options don't change young-gen sizing
  (with or without them: threshold 1 for Quarkus, 15 for Kotlin); `Min/MaxHeapFreeRatio` drive the
  old generation's sizing here.
- **glibc kept what startup and the JIT freed**: 49-63 MB of malloc NMT doesn't track stayed
  resident although nothing native is retained in steady state (`nativeleak`: 0.2 MB in 30 s).
- **Fixes, no heap caps:** `-XX:TrimNativeHeapInterval=5000` (35-56 MB less), and fewer bytes per
  request: the signed cookie's verification in Java (`SignedCookies`, 4.1 KB → 27 B), cached response
  header values encoded once, the request key without garbage, no duplicate cookie parse and routing:
  24.4 → 14.7 KB per request, 26% under Kotlin's 19.8. The young generation now peaks at ~108 MB
  under 16 connections at 2 cores, the tenuring threshold stays 15, and eden shrinks again at low load
  (slowly: 103 → 79 MB in 30 s at 10k req/s).
- **What is left:** at 16-256 connections Quarkus serves 1.4-2.1x Kotlin's requests, so its eden is
  still larger (anon +40-60 MB there); at 1 and 1024 connections it is at or below Kotlin. The
  remaining 14.7 KB per request is mostly the Kotlin port's kit (Ctx, middleware, headers, params,
  cookies), Vert.x's header entries and Netty's decoding: replacing the kit is the Quarkus-only phase.
  `-XX:MaxNewSize=64m` closes the gap (no throughput cost measured) but was rejected as a
  core-count-dependent tuning. Quarkus on JDK 25 is larger, not smaller (249-288 MB).

## Tried, no clean gain (FFM app, 2 interleaved reps or more)

- **`data_version` from the batch's own snapshot** (read on the reader connection inside the batch's
  read transaction, no observer connection): the SQLite thread's cost fell 11% (2.7 → 2.4 µs/req), but
  throughput didn't move (the SQLite thread is no longer the limit, below), batches got smaller (2.19
  vs 2.94 requests at 2 cores, 16 connections) and p99 there rose from 0.86-0.94 to 1.14-1.20 ms in 5 of
  5 rep pairs. Reverted. (A cache-invalidation check for it, `work/invalidate-check.sh`: a commit by
  another process must show in the next response; a build whose generation never advances fails it.)
- **Per-event-loop delivery of a batch's results** (one task per event loop instead of one
  `runOnContext` per request): 4-6% fewer req/s at 2 cores. Note that Quarkus runs each request on a
  duplicated context, so grouping by `Vertx.currentContext()` (an earlier attempt) grouped nothing.
- **The SQLite thread's queue**: JCTools' `MpscUnboundedXaddArrayQueue` with a parked-waiter handshake
  instead of `LinkedBlockingQueue`: same throughput within noise; kept (no lock on the producers'
  side).

## Where the 4-core limit is now: the event loops

With SQLite through FFM, the SQLite thread costs 2.4-2.7 µs/req and is on CPU 31-35% of the time at
4 cores and 1024 connections (schedstat). The 5 event loops use 3.0 cores (23.8 µs/req), each on CPU
60% of the time and runnable but waiting for a CPU 24-25%.

## Many connections: heap per connection and GC (2 cores)

All three apps lose throughput as connections grow (`final4-2c`, 1024 → 4096 → 16384): Rust 27.9k →
27.4k → 25.7k, Kotlin 42.4k → 40.6k → 36.3k, Quarkus 76.1k → 60.3k → 48.5k (-36%, the steepest; it
stays the fastest). CPU per request rises with them (Kotlin 40.1 → 46.1 µs, Quarkus 22.7 → 34.5
µs). A second driver (Hyperfoil's `wrk`, loadgen placement on node 1) measured the same Quarkus
decline within 2% at 4096 and 16384. For Quarkus:
Not the SQLite thread (on CPU 21%, 20%, 13%; idle 55-70%, schedstat). Not the kernel in steady
state (no TCP memory pressure, no zero-window drops; at 16384, 109-868 `ListenOverflows` and as many
SYN retries during the connects, with a 1024 accept backlog). GC grows with the connections:

| conns | GCs in 16 s | full GCs | pause total | old gen after GC | live heap (histogram) |
|---:|---:|---:|---:|---:|---:|
| 1024 | 151 | 0 | 290 ms | 42-53 MB | 34.9 MB |
| 16384 | 121 | 9 | 3,902 ms | 211-238 MB | 196.0 MB |

About 11 KB of live heap per connection. The largest share, 4.35 KB (`Object[]`, 1 per connection
with a Netty-shaded JCTools `MpscUnboundedArrayQueue`), is Vert.x 5.1.8's outbound message queue:
`VertxConnection`'s constructor (line 94) creates `strictThreadMode ? new DirectOutboundMessageQueue()
: new InternalMessageChannel(executor)`, and the latter allocates an `MpscUnboundedArrayQueue(1024)`
(`sipush 1024`) for the life of the connection. The rest: Netty pipeline contexts (4 per
connection), `IdleStateHandler`, the HTTP decoder, `Http1ServerConnection`, buffers.

`StrictThreadMode.java` turns on Vert.x' strict thread mode (`@Unstable`): writes must happen on the
connection's event loop, which all of this app's writes do (a write from another thread throws "Only
the context thread can write a message"). At 4096 connections it leaves 8 of those queues instead of
4,104 and the live heap at 45.3 MB instead of 63.9 MB, 0 errors, parity 0 different.
Throughput (2 cores, interleaved reps, medians): no difference measured. Against the previous
build, 2 reps: 74.2k / 74.5k req/s at 1024, 58.6k / 59.0k at 4096, 47.6k / 47.0k at 16384 (strict /
not); anon memory at 16384 414 MB / 543 MB. Same build with the property on and off, 4 reps: 69.2k /
68.4k at 16, 80.9k / 82.5k at 64, with reps of one app spread over 67-74k. So the queue is not
what costs throughput at many connections; the extra CPU per request is (schedstat, 1024 → 16384)
GC 0.4 → 10.7 µs and event loops 20.9 → 26.7 µs, the latter not explained yet. Both JVMs run with
`-Xmx512m` (`run-app.sh`). Reported to Vert.x with the measurements and alternatives (lazy
allocation, a 16-slot first chunk, a linked queue): eclipse-vertx/vert.x#6429.

Where the extra CPU goes (async-profiler `cpu`, 2 cores, 1024 vs 16384 connections, 26.5 vs 40.3
µs/req under the profiler; per-frame inclusive µs/req = sample share × µs/req,
`results/flamegraphs/quarkus-2c-{1024,16384}conns-cpu.html`):

| frame | 1024 | 16384 |
|---|---:|---:|
| GC worker threads (`WorkerThread::run`) | 0.37 | 10.32 |
| …young-GC root scanning (`ScavengeRootsTask::work`) | 0.36 | 8.71 |
| …of which old-gen card scanning (`PSCardTable::scavenge_contents_parallel`) | 0.29 | 6.38 |
| full GC (`PSParallelCompact`, share of samples) | 0% | 1.9% |
| event loops (`SingleThreadIoEventLoop.run`) | 22.92 | 26.73 |
| …request path (`HttpRoutes.lambda$staged$0`) | 18.42 | 20.93 |
| …response write (`HttpRoutes.write`) | 10.66 | 12.49 |
| …epoll ready-list processing (`EpollIoHandler.processReady`) | 3.93 | 5.30 |

Most of the growth is young collections scanning the old generation's card table, not full
GCs. I have not measured which old objects dirty those cards; that each request stores fresh
(young) request/response objects into long-lived per-connection objects is a guess.

## Not done

- **Auth reads on cache hits.** A response-cache hit still runs 5 SQLite statements (2 × `PRAGMA
  data_version`, session, user, room membership), as Rust and Kotlin do. Caching them per
  `data_version` generation would let a hit be served from Caffeine on the I/O thread alone.
- **One read transaction per request.** In WAL mode each autocommit statement opens and closes its
  own read transaction: ~11 `unixShmLock` calls per request (measured, uprobes, both apps). The
  staged architecture uses one transaction per batch instead.

## Measured, outside the apps

- **`unixShmLock` is process-wide.** Every connection to one database file in a process shares one
  WAL-index mutex, taken at the start and end of every statement. It is contended as soon as
  several threads run SQLite (native-lock profile: 92% of Quarkus' contended waits). At one core,
  1, 2 or 4 SQLite threads gave the same CPU per request (81–88 µs).
- **The loadgen parsed its contract once per connection, inside the measured window** (fixed:
  a patch in the earlier harness; the current one uses Hyperfoil instead). `Validator::from_file` reads and
  parses the 1.87 MB contract for every connection before spawning it, about 7.5 ms each, one after
  another, after the step's clock has started. A 1 s step took 1.95 s at 256 connections, 7.75 s at
  1024 and 30.9 s at 4096 (1.02-1.14 s without `--validate`). At 4096 only 1,916 and 1,881
  connections were ever opened (`TcpActiveOpens`) before the 15 s deadline, and req/s was divided
  by 31 s: that was the "halving at 4096" of all three apps. At 1024 the connections started over
  the first ~7.7 s of each 15 s step, for every app; every 1024-connection number in RESULTS.md was
  measured that way. With the patch the contract is parsed once and shared (the expected body behind
  an `Arc`): a 1 s step at 4096 connections takes 1.22 s, the loadgen's 20 tests pass, and a contract
  with one byte flipped fails 68,991 of 68,991 responses. **Correction:** this note used to blame the
  kernel (`ListenOverflows` 1,303, `TCPSynRetrans` 1,237 in one run). Those counters were real, but I
  never checked that a second of SYN retries per dropped connection could account for 16 lost seconds,
  nor counted the connections opened against those asked; it can't, and the count gives it away.
- **Run-to-run drift.** The same Kotlin build measured 24.3k and 27.3k req/s at 64 connections an
  hour apart, cause unknown. Only interleaved runs (same session, alternating order) are compared.

## Scalability bottlenecks of the previous design (handler on a worker pool)

Solved by the staged architecture (only one thread calls SQLite); kept for the record. Measured at
2 physical cores (app on CPUs 2,3; loadgen on 4-7, NUMA node 0), 1024 connections unless stated,
Quarkus 4 with 2 event loops and 4 workers.

- **SQLite's WAL-index mutex (`unixShmLock`)** is process-wide: ~11 acquisitions per request
  (uprobes, both apps), 88–94% of contended native-lock waits. A contended wait lasts ~260 µs in
  Quarkus vs ~50 µs in Kotlin on average (native-lock profile); likely lock holders descheduled, not
  measured directly.
- **The `data_version` observer lock**: one connection, read twice per request under a monitor by
  all workers. Its spinning costs 0.17–0.28 µs/req of CPU (Kotlin 0.05–0.07); a `ReentrantLock` was
  worse (35–36k vs 47–48k req/s). In steady state every read returns the same value (nothing
  commits after the first request). Untested: `data_version` itself as the generation with one
  statement per worker on the observer connection (same work, no lock of ours).
- **Too few workers stall**: 2 workers give 29–32k req/s with each ~45% off-CPU, while Kotlin's 2
  workers reach ~45k. 1 worker caps at 28–29k (the handler needs ~1.1 cores). Cause of the off-CPU
  time not measured.
- **Low concurrency**: at 16 and 64 connections Quarkus is at 79–88% of Kotlin with its CPUs not
  saturated (1.64 vs 1.83 of 2 at 16 connections). The extra hand-off (event loop → worker → event
  loop) is a suspect, not measured.
- **sqlite-jdbc per-call cost**: 34–52 JNI calls per request, each `synchronized`; at 1 core the JNI
  and Java glue cost +6.4 µs/req over Kotlin's FFM path (profile).

## Driver and runtime differences

- **SQLite through FFM, as Kotlin.** `Sqlite.java` is a Java port of the Kotlin port's FFM bindings
  (`Sqlite.kt`), against the same library (the Rust port's amalgamation and flags, built by the
  Kotlin port's `native/build.sh`). Binds and column reads are `critical` downcalls (memory copies
  only); step, reset, exec and open are not (they take SQLite's file and WAL-index locks, do I/O,
  or wait out the busy timeout). No JDBC, no connection pool: the SQLite thread owns its two
  connections, and the response cache's `data_version` observer has its own read-only one.
- **sqlite-jdbc, before FFM** (history in git, up to commit a5fab63): with Agroal and the released
  sqlite-jdbc, 34–52 JNI calls per request, each `synchronized`. Two patches were measured:
  caching column names (wrong after a schema change: SQLite re-prepares the statement and a
  `select *` can gain columns; reproduced with `ALTER TABLE ADD COLUMN`) and skipping redundant
  resets (JNI calls 51.8 → 34.0, no gain at 2 cores, +31% at 4 cores where the SQLite thread is the
  limit). Quarkiverse `quarkus-jdbc-sqlite` has no Quarkus 4 release.
- **Quarkus 4 native transport is opt-in at build time** (`quarkus.vertx.native-transport`,
  default `disabled` = NIO); set to `required` + `epoll`, the transport Kotlin uses.

## Connection handling (informational parity)

`bench/requests-extra.txt` (HTTP/1.0, `Connection: close`): Quarkus differs from Rust in 2 cases,
Kotlin in 4. Not part of the published parity checks.
