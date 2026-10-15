# Campfire room page: Quarkus vs the Rust and Kotlin ports

37signals published a [Rust port](https://github.com/basecamp/once-campfire-rust) of Campfire with
a benchmark against the Rails original, and [Kotlin keeps up with Rust](https://www.wecodefire.com/p/kotlin-keeps-up-with-rust)
answered with a [Kotlin port](https://github.com/mysticcoders/once-campfire-kotlin) measured the same
way ([ARTICLE.md](ARTICLE.md)). This repository adds a third contender, **Quarkus 4 on JDK 27**, and a
harness anyone can run on their own Linux machine to compare the three.

The benchmark is one page: `GET /rooms/486777696`, the busy room of the shared seed (40 messages),
signed in. Every app must return **the same bytes** as the Rust port.

## The challenge

The Quarkus app here is fast, but it is not what a typical Quarkus app looks like: one Vert.x route
instead of REST endpoints, SQLite called through Java's FFM API on a dedicated thread instead of
JDBC, hand-written batching. What we found is that this architecture was the key to the speed (see
below). Nothing requires keeping it: any design is welcome, and the invitation to Quarkus
performance experts is

**make it more idiomatic Quarkus, and still beat every other port.**

The rules:

1. **Same output.** `./bench.sh parity` reports 0 different against the Rust port, and the smoke test
   before every measurement passes.
2. **Tests stay green and unmodified.** The tests in `quarkus4-app/src/test` check the cookie
   handling, request keys and routes; add tests, don't weaken them.
3. **Reuse is fine where it guarantees correctness.** The app uses the Kotlin port's Rails-format
   modules (cookies, routes, templates, rich text) as jars so its output matches byte for byte; keep
   them, change them in the fork the app builds them from (`upstream/once-campfire-kotlin-pooled`,
   disclosed in DIFFERENCES.md), or replace them with your own, as long as the output stays the same.
   The Kotlin app itself is always measured as published.
4. **Same measurement, both workloads.** Two runs decide, each on the same machine and cores as the
   version you compare against, with the other ports measured in the same run:
   - `./bench.sh run`: the article's matrix, one session hammering one cached page, all out (cache
     hits): requests per second;
   - `./bench.sh run --matrix realistic`: many sessions loading their rooms while presence and
     messages are written, at page-load rates you choose for your machine (`--rate`; the defaults,
     333, 833 and 1,667 pages/s, are 20k, 50k and 100k visible tabs; mostly cache misses): pages
     served, p99 and CPU per page.

   A change should not lose on either against the version it replaces.

Open a PR with your change and both `results/runs/...` directories.

## Results

On a Ryzen 9 7950X at a fixed 4.3 GHz, each app on 4 physical cores:

| | Kotlin | Quarkus | Rust |
|---|---:|---:|---:|
| All out, 16 connections: req/s (median of 3 reps) | 47,119 | 107,634 | 44,572 |
| All out, 1,024 connections: req/s (median of 3 reps) | 98,640 | 133,957 | 48,048 |
| Realistic, 333 page loads/s offered: CPU per page, p99 (Kotlin and Rust 1 rep, Quarkus 3) | 4.6 ms, 17.2 ms | 3.6 ms, 5.0 ms | 2.9 ms, 4.0 ms |
| Realistic, 1,667 page loads/s offered: pages/s served (Kotlin and Rust 1 rep, Quarkus 3) | 668 | 1,654 | 1,079 |

Quarkus is the current design (below, "After"). Kotlin's and Rust's numbers come from the previous
runs, their code unchanged since. All out, Quarkus serves the most requests with the least CPU per
request, and uses more memory than Kotlin. On the realistic workload Rust is the leanest at low load,
and Quarkus serves the most at saturation: offered 1,667 page loads/s, it serves 1,654 where Rust
serves 1,079 and Kotlin 668, and offered 5,000 over the same 2,048 connections it still serves 4,843
with CPU to spare (one run, [results/README.md](results/README.md)); the 512 MB heap, not the CPU,
is what bounds it when more connections put more requests in flight. The full tables, CPU per
request, memory and flame graphs: [results/README.md](results/README.md). How the earlier designs compared, run by run:
[RESULTS.md](RESULTS.md). What was found along the way, including what was not fixed: [NOTES.md](NOTES.md).

## What every request does

Even when the finished page is cached, every request in every app:

1. parses the Rails `session_token` cookie and verifies its signature (HMAC);
2. reads SQLite's `PRAGMA data_version` (has anything been written since the page was cached?);
3. reads the session, then the user (once an hour it also writes the session's last activity);
4. reads the room, through the user's membership;
5. hashes the request (generation, user, session, path, headers) into the cache key;
6. reads `data_version` again, then serves the cached page (24 KB gzipped) with a fresh request id,
   runtime, date and cookies.

So the benchmark is mostly about steps 1–6 around a cache hit: SQLite reads, a little crypto, and
writing 24 KB well. (Quarkus reads `data_version` before and after a batch of requests' reads, not
around each request: below.)

A real Campfire commits often (every open tab refreshes its presence every 50 s, every message marks
the room unread for its members), and each commit makes the next page a miss: rendered and gzipped
from scratch. `--matrix realistic` measures that case (Running, below).

## How the Quarkus app got fast: two designs

### Before: the typical design

A Quarkus app would normally run the handler on a worker pool: each request goes from the event
loop to a worker thread, which runs everything (cookie, five SQLite statements through JDBC and a
connection pool, cache key, cache lookup) and hands the response back.

That design was slower than Kotlin at low concurrency. Profiling showed why:

- **SQLite has one lock for the whole process.** Every statement on a WAL database takes SQLite's
  WAL-index mutex twice, and that mutex is shared by every connection in the process. With several
  worker threads running statements, they queue on it: 88–94% of the waits on native locks were this
  one mutex (async-profiler's native-lock profile; uprobes count about 11 acquisitions per request).
- **JDBC adds a cost to every call.** sqlite-jdbc makes 34–52 JNI calls per request, each one
  `synchronized`.

### After: the staged design

- **The event loops** do everything that is not SQLite: HTTP, the cookie's HMAC, the cache key, the
  cache lookup, the headers, the write.
- **One SQLite thread** does every SQLite call, and nothing else. Requests queue to it and it serves
  them in arrival order: each request's own session, user and room reads in read transactions shared
  by a batch (at most 256 reads or 2 ms each), with `data_version` read before and after.
- **A virtual thread per request** runs a request that needs more (a cache miss, the hourly session
  write, any other route): it renders and gzips there, and each SQL call it needs is forwarded to the
  SQLite thread, the virtual thread unmounting while it waits. A room page reads all its rows in one
  forwarded call.
- **SQLite through FFM** (`Sqlite.java`, a Java port of the Kotlin port's bindings, against the same
  SQLite build): no JDBC, no pool, statements prepared once.
- **The fragment cache has no lock around the whole store.** A room page is 40 message fragments
  (a message's rendered HTML, cached), each cached under the database generation it was rendered in
  (SQLite's `data_version`: every commit starts a new one, so nothing stale is ever reused; the Rust
  design). The store is a Caffeine cache: a request locks only the entry it reads or writes. With
  one lock around the whole store, as the Kotlin port has it, the virtual threads rendering pages and
  the SQLite thread prefetching all queued on that one lock.
- **Compression through FFM too.** Each page is gzipped piece by piece (a piece: one fragment's
  compressed bytes) with the same dictionary scheme as the other ports, so it decompresses to the same
  page. The pieces are deflated by the system's zlib (zlib-ng on Fedora) called directly, reading the
  fragment in place, instead of the JDK's bundled zlib behind `java.util.zip`; `CAMPFIRE_DEFLATE=jdk`
  switches back.
- **Where cached fragments live is a switch.** On the heap by default (`CAMPFIRE_FRAGMENT_MEMORY=heap`,
  as the Kotlin port keeps them), or in native memory with reference counting (`native`): the
  generation-keyed cache's entries live just long enough to be promoted and then die, which the
  heap pays for in old-generation collections and native memory does not; the native store is
  leaner in latency tails, the heap in memory. Both are measured in NOTES.md.

What it solves: only one thread ever takes SQLite's lock, so nobody waits for it; one transaction
per batch instead of one per statement; no SQLite call ever blocks an event loop; and a slow render
never holds up the SQLite thread or a cache hit. (Until `ec9ef9c`, misses ran on the SQLite thread
itself: under frequent commits they queued in front of every hit, NOTES.md "Under writes".)

Why it serves more pages the more it is offered: the requests in one SQLite batch read the same
generation, so their pages share every fragment rendered for it; the busier the server, the bigger
the batches and the fewer fragments each page renders itself (nearly all 40 at 333 pages/s, about 8
at 3,333). Rust observes the generation per request, so its pages never share. Offered 5,000 pages/s
over 2,048 connections, Quarkus served 4,843 with half a CPU to spare; what stops it first is the
512 MB heap, when more connections put more requests in flight at once (results/README.md). The flip side, under
saturation only: a page's data is read when it is looked up and sent after the render queue, so at
send time it is as old as that queue (hundreds of milliseconds at 1,667 pages/s, from the request
timers; not measured against the database directly), where Rust reads right before it sends.

Before and after, same machine, 2 cores, 16 connections, Kotlin alongside for reference:

| | Quarkus req/s | Kotlin req/s |
|---|---:|---:|
| worker pool + JDBC (before) | 33,481 | 42,579 |
| staged + FFM (now) | 72,316 | 40,900 |

The FFM part on its own (the staged app with JDBC or FFM, 4 cores, 1024 connections): 95–97k vs
129–135k req/s.

### Why not JDBC

Not because JDBC is slow in general: because of what this driver does on this workload. sqlite-jdbc
crosses JNI for every value and every step, under a lock per connection; it has no statement cache
([xerial/sqlite-jdbc#614](https://github.com/xerial/sqlite-jdbc/issues/614)); its native calls pin
virtual threads ([#1136](https://github.com/xerial/sqlite-jdbc/issues/1136)); and the Quarkiverse
SQLite extension has no Quarkus 4 release. With one thread doing all SQLite work, a pool buys nothing,
and FFM calls SQLite directly. A Quarkus-idiomatic way back to JDBC that keeps the speed is part of
the challenge.

### Smaller things that mattered

Each was measured on its own, before and after, in [NOTES.md](NOTES.md):

- The cached page is kept in native memory and written from there: no copy per response.
- Netty's low-memory mode is off: with a 512 MB heap Netty assumed it was short on memory and
  allocated each 24 KB write with `malloc`.
- Vert.x strict thread mode: Vert.x keeps a 4.7 KB queue per connection unless every write happens on
  the connection's event loop, which here they do. Reported upstream:
  [eclipse-vertx/vert.x#6429](https://github.com/eclipse-vertx/vert.x/issues/6429).
- The signed cookie is verified by a Java fast path, byte for byte equal to the Kotlin port's.
- The JVM options of Red Hat's UBI images, so the heap is given back to the OS.
- The Rust port's two indexes on `messages`, created at boot as Rust creates them: without them every
  page that isn't a cache hit sorts the room's history (DIFFERENCES.md).

## Fairness: how we know every app does the same work

- `./bench.sh parity` runs Rust (the reference) and Quarkus or Kotlin side by side and compares
  status, body and headers in order for the room page (both user-agent branches, both encodings) and
  the 34 requests of the Kotlin port's `bench/requests.txt` (cache miss and hit, HEAD, 304, Turbo
  frames, bad cookies, 404s, assets, ranges). Only values that must differ are masked: dates,
  request ids, runtimes, signed cookie values, a gzipped body's length. Quarkus: 43 checks, 0 different.
- Before every measurement, after each app starts, `bench.sh` sends the measured request 100 times
  and compares each decoded body with the Rust port's page, byte for byte; any difference stops the
  run.
- All three apps start from the same seed, with the same environment, one at a time, on the same
  CPUs.
- The Rust and Kotlin ports are built as published, from the commits in `upstream/`; the Quarkus app
  takes the Kotlin modules from a fork (DIFFERENCES.md). (The Kotlin
  article measured Rust with a patch to its listen backlog; this repository leaves it as shipped:
  [DIFFERENCES.md](DIFFERENCES.md).)

## Requirements

- Linux on x86_64, with `bash`, `git`, `curl`, `cmp`/`diff`, a C compiler, `taskset`, `lscpu` and `flock`
  (util-linux), `nstat` (iproute2).
- [SDKMAN](https://sdkman.io) with `sdk install java 25.0.3-tem` (the Kotlin port, as published) and
  `sdk install java 27.0.0-tem` (Quarkus).
- [jbang](https://www.jbang.dev): it runs the report, Hyperfoil and async-profiler; `jbang-catalog.json`
  pins their versions.
- A Rust toolchain (`cargo`) and the libvips development files, to build the Rust port.
- The system zlib (`libz.so.1`): the Quarkus app calls it through FFM to compress pages
  (`CAMPFIRE_DEFLATE=ffm`, the default). On Fedora it is zlib-ng, on Debian and Ubuntu classic zlib;
  `config.txt` records which, and `CAMPFIRE_DEFLATE=jdk` uses the JDK's bundled zlib instead.
- At least 13 physical cores for the default 4-core placement (4 for the app, 8 for the load driver,
  one left to the OS), or fewer with `--cores`, or pass the CPUs yourself.
- Network access during `setup`: the ports, Maven Central, crates.io, Gradle's distribution, jbang's
  catalogs.

For stable numbers, fix the CPU frequency first, e.g. `sudo cpupower frequency-set -g performance
-d 4.3GHz -u 4.3GHz` and turn boost off (`echo 0 | sudo tee /sys/devices/system/cpu/cpufreq/boost`; on Intel
`echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo`).
`bench.sh` reports the governor and boost state but does not change them.

## Running

```sh
git clone --recurse-submodules <this repo> && cd campfire-quarkus-bench
./bench.sh setup                  # builds the Rust and Kotlin ports and the Quarkus app
./bench.sh parity                 # must report "0 different"
./bench.sh run --matrix quick     # Quarkus vs Kotlin, 4 cores, two connection counts, ~5 minutes
./bench.sh run                    # all out, the full matrix (bench/matrix/article.conf), ~30 minutes
./bench.sh run --rate 20000,40000   # fixed rates instead: each app at each rate you choose
./bench.sh run --matrix realistic --rate 333,833,1667   # many users: page loads/s with their writes, ~25 minutes
```

`--matrix realistic` (`bench/Workload.java`, `bench/realistic*.hf.yaml`) adds sessions for the seed's
people and loads their rooms at the page-load rates you give (`--rate`, the open model), with the
writes the visible tabs behind those page loads make: a tab loads one of its rooms every 60 s,
refreshes its presence every 50 s (as Campfire's client does) and posts a message every 10 min
(`--page-every`, `--presence-every`, `--message-every`), so 1.2 presence commits and 0.1 messages per
page load. A writer outside the apps commits them to each app's database during each step, the same
rows for every app, on the core left to the OS. Every (person, room) page is compared with the Rust
port's first. Not modelled: WebSockets (broadcasts, idle connections) and writes inside the apps.

`run` picks the CPUs: the app gets `--cores` physical cores (one SMT thread each) on one NUMA node,
the load driver twice as many, never on the app's cores. To choose them yourself:
`--app-cpus 0-3 --driver-cpus 8-15`. Other options: `--apps quarkus,kotlin`, `--conns 64,1024`,
`--reps 1`, `--rate 20000,40000`; `./bench.sh --help`
lists them. Runs on one machine wait for each other (a lock file, `BENCH_LOCK`). A matrix is a small file of settings in
`bench/matrix/`; add your own.

Each run writes `results/runs/<date>-<cores>c/`, prints its tables at the end and draws SVG charts into its
`charts/` (throughput, CPU per request, memory);
`./bench.sh report <dir>` prints them again (`--markdown` for a README). Problems seen on either side
(client errors, connections that never opened, listen-queue overflows, time spent waiting for a
connection, error lines in an app's log, a saturated load driver) are listed under the tables.

### Where things run, and why

`bench.sh run` places the app and the load driver on CPUs so that what is measured is the app:

- **The app and the driver never share a physical core**, so neither steals cycles, caches or
  execution units from the other.
- **One SMT thread per core.** Each component uses one hardware thread of each of its cores and the
  sibling stays idle: "4 cores" means 4 whole cores, with nothing running on their second threads.
- **One NUMA node per component, the app's node first.** Linux allocates memory on the node of the
  CPU that touches it first, so pinning the CPUs also keeps each process's memory local. The driver
  goes on the app's node when it fits, otherwise on the next one; neither ever spans two nodes.
- **The node's first core is left to the OS**: interrupts, the desktop and other housekeeping
  usually run there.
- **The driver gets twice the app's cores**, so the app saturates before the driver does. The report
  flags any step where the driver's CPUs were more than 80% busy: that number would measure the
  driver, not the app.
- **Nothing else may run there.** Before measuring, `bench.sh` checks that those CPUs are idle and
  refuses otherwise (`--force` overrides), and it reports the frequency governor and boost state.

`lscpu -e=CPU,CORE,NODE` shows your topology. On the 7950X used for `results/` (2 nodes of 8 cores,
CPUs n and n+16 are siblings), 4 app cores land on CPUs 1-4 and the driver on 8-15, the other node,
since node 0 has no room for 8 more cores. To place them yourself: `--app-cpus 1-4 --driver-cpus 8-15`.

Both phases use one load tool, [Hyperfoil](https://hyperfoil.io), sending the same request
(`bench/room.hf.yaml`, `bench/room-rate.hf.yaml`):

- **All out (closed loop)**: N connections, each sending its next request when its response is in.
  20 s warmup at 16 connections after every start, 15 s per step after a 5 s warmup, 3 reps in
  alternating app order, medians.
- **Fixed rates (`--rate 20000,40000`; an "open model": requests arrive on schedule, whatever the app
  does)**: each app at each rate you give, through 2,048 connections, arrivals evenly spaced, each
  request timed from when it was due, so a stall shows in the latency.

### Why Hyperfoil

[Hyperfoil](https://hyperfoil.io) ([docs](https://hyperfoil.io/docs/)) is a load driver built to
measure servers without fooling itself:

- **Both load models in one tool.** All out (a fixed number of users, each waiting for its
  response) and fixed rates (users arriving on a schedule, whatever the server does), described in
  two small YAML files anyone can read and change.
- **Latency without coordinated omission.** In the open model a request is timed from when it was
  due, not from when it was sent (`compensateInternalLatency`), so a server stall shows up in the
  percentiles instead of silently slowing the load down.
- **It says when it is the bottleneck.** It reports time spent waiting for a free connection and
  sessions beyond its preallocated pool, and `bench.sh` flags both.
- **Arrival shape matters, and it is explicit here.** We first ran the fixed rates with oha, as the
  Kotlin article did: oha releases each millisecond's requests together, which lets an app serve
  them in batches. Spread evenly (Hyperfoil, `variance: false`), the same rate cost every app more
  CPU per request, up to 37% more: the same instructions per request, but two to four times the
  wake-ups (measured with `perf stat`; Quarkus' SQLite thread woke 6.6 times as often). The even
  shape is the harder, more realistic one, so it is the one we measure.
- **Reproducible.** jbang runs it at the version pinned in `jbang-catalog.json`; nothing else to install.

Each step is a Hyperfoil run: a warmup phase, then the measured phase under the same load, starting
only when the warmup has ended. Req/s and latency come from the measured phase; CPU per request is
the app's CPU time over the whole run divided by the responses of both phases.

How this differs from DHH's runs and from the Kotlin article's, and why: [DIFFERENCES.md](DIFFERENCES.md).

`./bench.sh profile --app quarkus --conns 1024` records a CPU flame graph of one app under the load of
one run step (all out at `--conns`; or the first `--rate`; or, with `--matrix realistic`, its first
rate with the writer) with async-profiler, into `results/profiles/`: 10 s at one sample per millisecond of CPU (a
frame's samples are its CPU time in ms), the JVM started with `-XX:+DebugNonSafepoints` so inlined
code is attributed to the right frame. `--event alloc`, `wall` or `lock` record other
events; `--format jfr` writes JFR instead, and allows several events at once
(`--event cpu,alloc,lock --format jfr`); `AP_OPTS` passes more async-profiler options.

JFR files can be explored by an AI agent: `.mcp.json` declares the [Jafar](https://github.com/btraceio/jafar)
MCP server (run by jbang), which agents such as Claude Code pick up when opened in this directory.

## A note on effort

The Kotlin article reported what its port cost; here is the same for the Quarkus app, built with
Claude Code (Claude Opus 5.5), counted from the session transcripts. **It is not a like-for-like
comparison:** the Quarkus app reuses the Kotlin port's Rails-format modules (about 22,000 lines:
cookies, routes, templates, rich text) as jars, and wrote only the app layer around them, while the
Kotlin port wrote all of it.

| | Kotlin port (the article) | Quarkus, first working version | Quarkus, all of it (to 2026-10-13 13:47) |
|---|---:|---:|---:|
| wall-clock (includes benchmark runs and waiting) | ~24 h, overnight included | 1.5 h | 25.4 h |
| active (gaps under 15 minutes between messages) | not reported | 0.8 h | 16.4 h |
| fresh tokens (generated + written to the prompt cache) | ~5.3M | 0.52M | 6.2M |
| cache reads | ~410M | 33M | 696M |
| code written | ~22,000 lines of Kotlin | ~1,400 lines of Java | ~2,650 lines of Java, 240 of tests |

Tokens are spent only when the model takes a turn, not while a benchmark runs: most of the last
column's wall-clock time was the machine measuring. Cache reads count the conversation re-read at
every turn, so they grow with how often the session checked in, not only with the work done.

"First working version": parity with Rust (0 different) and a first measurement against Kotlin, on
one core, where it was still slower (18.5k vs 21.5k req/s). Everything after that (Quarkus 4, the
staged architecture, FFM, the measurements behind [NOTES.md](NOTES.md) and this harness) is in the last column.

## Layout

```
bench.sh                 the only entry point
bench/                   Report.java (run by jbang), the Hyperfoil scenarios, the matrices, extra
                         parity requests
quarkus4-app/            the Quarkus app
upstream/                the Rust port and the Kotlin port (git submodules, at the commits measured), and the
                         fork of the Kotlin port whose modules the Quarkus app is built from
fixtures/                the seed database and the session cookie (fixtures/README.md)
results/                 this machine's results and the data behind them
```

