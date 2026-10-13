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
   modules (cookies, routes, templates, rich text) as jars so its output matches byte for byte;
   keep them or replace them with your own, as long as the output stays the same.
4. **Same measurement.** Numbers come from `./bench.sh run`, on the same machine and the same cores
   as the version you compare against, with the other ports measured in the same run.

Open a PR with your change and its `results/runs/...` directory.

## Results

On a Ryzen 9 7950X at a fixed 4.3 GHz, each app on 4 physical cores, all out (req/s, medians of 3 reps):

| connections | Kotlin | Quarkus | Rust |
|---:|---:|---:|---:|
| 16 | 47,119 | 107,621 | 44,572 |
| 1,024 | 98,640 | 133,580 | 48,048 |

Quarkus also uses the least CPU per request, and more memory than Kotlin. The full tables, CPU per
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
writing 24 KB well.

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
- **One SQLite thread** does every SQLite call. Requests queue to it and it serves them in batches:
  one read transaction per batch, inside it each request's own session, user and room reads, with
  `data_version` read before and after. A request that needs more (a cache miss, the hourly session
  write, any other route) runs again on that thread.
- **SQLite through FFM** (`Sqlite.java`, a Java port of the Kotlin port's bindings, against the same
  SQLite build): no JDBC, no pool, statements prepared once.

What it solves: only one thread ever takes SQLite's lock, so nobody waits for it; one transaction
per batch instead of one per statement; and no SQLite call ever blocks an event loop.

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

## Requirements

- Linux on x86_64, with `bash`, `git`, `curl`, `cmp`/`diff`, a C compiler, `taskset`, `lscpu` and `flock`
  (util-linux), `nstat` (iproute2).
- [SDKMAN](https://sdkman.io) with `sdk install java 25.0.3-tem` (the Kotlin port, as published) and
  `sdk install java 27.0.0-tem` (Quarkus).
- [jbang](https://www.jbang.dev): it runs the report, Hyperfoil and async-profiler; `jbang-catalog.json`
  pins their versions.
- A Rust toolchain (`cargo`) and the libvips development files, to build the Rust port.
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
./bench.sh run --rate           # ...then fixed rates, derived from those results, ~25 minutes more
```

`run` picks the CPUs: the app gets `--cores` physical cores (one SMT thread each) on one NUMA node,
the load driver twice as many, never on the app's cores. To choose them yourself:
`--app-cpus 0-3 --driver-cpus 8-15`. Other options: `--apps quarkus,kotlin`, `--conns 64,1024`,
`--reps 1` (all-out steps; the fixed-rate phase uses the matrix's `OPEN_REPS`), `--rate`; `./bench.sh --help`
lists them. Runs on one machine wait for each other (a lock file, `BENCH_LOCK`). A matrix is a small file of settings in
`bench/matrix/`; add your own.

Each run writes `results/runs/<date>-<cores>c/` and prints its tables at the end;
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
- **Fixed rate (`--rate`; an "open model": requests arrive on schedule, whatever the app does)**: offered loads of 0.5 to 1.4 times the best app's all-out rate at 64
  connections, through 2,048 connections, arrivals evenly spaced, each request timed from when it
  was due, so a stall shows in the latency.

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

`./bench.sh profile --app quarkus --conns 1024` records a CPU flame graph of one app under all-out
load with async-profiler, into `results/profiles/`. `--event alloc`, `wall` or `lock` record other
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
| wall-clock | ~24 h | 1.5 h | 25.4 h |
| fresh tokens (generated + written to the prompt cache) | ~5.3M | 0.52M | 6.2M |
| cache reads | ~410M | 33M | 696M |
| code written | ~22,000 lines of Kotlin | ~1,400 lines of Java | ~2,650 lines of Java, 240 of tests |

"First working version": parity with Rust (0 different) and a first measurement against Kotlin, on
one core, where it was still slower (18.5k vs 21.5k req/s). Everything after that (Quarkus 4, the
staged architecture, FFM, the measurements behind NOTES.md and this harness) is in the last column.

## Layout

```
bench.sh                 the only entry point
bench/                   Report.java (run by jbang), the Hyperfoil scenarios, the matrices, extra
                         parity requests
quarkus4-app/            the Quarkus app
upstream/                the Rust port and the Kotlin port (git submodules, at the commits measured)
fixtures/                the seed database and the session cookie (fixtures/README.md)
results/                 this machine's results and the data behind them
```

### Nothing is patched

The Rust and Kotlin ports are built from their repositories at the commits measured, unmodified.
