# Results on the reference machine

AMD Ryzen 9 7950X (2 NUMA nodes of 8 cores, SMT), every CPU fixed at 4.3 GHz, boost off, Linux 7.1.
Each app on 4 physical cores (CPUs 1-4, one thread per core), Hyperfoil on 8 cores of the other node
(CPUs 8-15), the ports built from the commits in `upstream/`, unmodified. Produced from a fresh clone
with:

```sh
./bench.sh setup && ./bench.sh parity quarkus && ./bench.sh parity kotlin   # 43 checks, 0 different, each
./bench.sh run --apps quarkus                                              # the article matrix, all out
./bench.sh run --matrix realistic --rate 333,833,1667 --reps 1             # the realistic workload
```

The realistic workload's writer runs on the core left to the OS (CPUs 0 and 16).

Two runs, with the current design (commit `9390b49`; `config.txt` in each has every setting, the JVM
options and the commits; `report.txt` is the output of `./bench.sh report` on it):

- [`runs/20261014-160945-4c/`](runs/20261014-160945-4c/): the article matrix, all out, Quarkus only,
  3 reps (`./bench.sh run --apps quarkus`).
- [`runs/20261014-161836-4c/`](runs/20261014-161836-4c/): the realistic workload, all three apps,
  1 rep (`./bench.sh run --matrix realistic --rate 333,833,1667 --reps 1`).

Kotlin and Rust in the all-out table come from the previous run,
[`runs/20261013-143448-4c/`](runs/20261013-143448-4c/): their code did not change since (the same
commits, `once-campfire-kotlin 87436f3` and `once-campfire-rust 6dae2fd`), and the machine, JDKs, JVM
options and matrix are the same (compare the `config.txt` files).

## All out, 4 cores (medians of 3 reps)

| conns | Kotlin req/s | Quarkus req/s | Rust req/s | Quarkus / Kotlin | µs CPU/req K / Q / R | p99 ms K / Q / R | RSS MB K / Q / R |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 15,006 | 16,707 | 9,910 | 111% | 50.6 / 44.5 / 96.4 | 0.1 / 0.1 / 0.1 | 224 / 251 / 52 |
| 16 | 47,119 | 107,634 | 44,572 | 228% | 60.3 / 28.2 / 74.8 | 0.8 / 0.8 / 0.7 | 225 / 315 / 52 |
| 64 | 67,734 | 124,965 | 47,507 | 184% | 43.2 / 24.4 / 69.2 | 2.0 / 2.5 / 2.6 | 238 / 317 / 56 |
| 256 | 92,898 | 125,008 | 53,145 | 135% | 33.7 / 24.1 / 60.6 | 6.3 / 5.3 / 8.5 | 285 / 321 / 68 |
| 1024 | 98,640 | 133,957 | 48,048 | 136% | 31.9 / 24.2 / 68.9 | 16.5 / 13.8 / 41.7 | 359 / 368 / 109 |
| 4096 | 88,841 | 122,471 | 44,971 | 138% | 35.1 / 27.5 / 75.5 | 58.7 / 57.1 / 331.4 | 382 / 420 / 263 |

- **Quarkus serves the most requests at every connection count**, with the least CPU per request.
- **It also uses more memory than Kotlin at every connection count**: 420 vs 382 MB at 4,096
  (peak 640 MB), more heap per connection and more garbage collection at many connections, in
  [NOTES.md](../NOTES.md), "Many connections".
- **Against the previous design** (`ec9ef9c`, the previous run): within 2% up to 1,024 connections,
  2.8% lower at 4,096 (122,471 vs 125,983 req/s).
- **Rust is the unmodified port**: its listen backlog is 128, and at 1,024 and 4,096 connections the
  kernel dropped 75-3,650 connection attempts per step before they were accepted (retried, so every
  connection opened). The Kotlin article measured Rust with a patch for this; this repository changes
  no port's code ([DIFFERENCES.md](../DIFFERENCES.md)).
- Every app passed the smoke test before every measurement. One problem was flagged: in Quarkus' second
  rep at 4,096 connections the kernel dropped 319 connection attempts (listen-queue overflows; retried,
  every connection opened). No step had client errors, missing connections, error lines in an app's
  log, waits for a connection in the measured phase, or a saturated load driver.

Charts of the previous run (Quarkus with the `ec9ef9c` design):
[throughput](runs/20261013-143448-4c/charts/throughput.svg),
[CPU per request](runs/20261013-143448-4c/charts/cpu-per-request.svg),
[memory](runs/20261013-143448-4c/charts/memory.svg); this run's, Quarkus only:
[throughput](runs/20261014-160945-4c/charts/throughput.svg).

**Why Quarkus slows down, and grows, with many connections.** From 1,024 to 4,096 connections its
throughput drops 9% and its memory rises from 368 to 420 MB (peak 640). What the investigation measured
([NOTES.md](../NOTES.md), "Many connections", at 2 cores up to 16,384 connections):

- **Every open connection keeps objects alive**: about 11 KB of heap each (Netty's and Vert.x'
  per-connection state, buffers), about 200 MB at 16,384 connections, for as long as they stay open.
  That was measured before one fix: 4.7 KB of it was a Vert.x queue per connection, removed here by
  Vert.x's strict thread mode and reported upstream
  ([eclipse-vertx/vert.x#6429](https://github.com/eclipse-vertx/vert.x/issues/6429)).
- **The garbage collector pays for them on every young collection**: it has to scan the old
  generation's cards that point into the young one, and that scan grew from 0.3 to 6.4 µs per
  request between 1,024 and 16,384 connections (CPU profile; [flame graphs](#where-the-time-goes)).
  Full collections happened too, but cost far less CPU (1.9% of the samples).
- **The heap is capped at 512 MB for both JVMs** (`-Xmx512m`), and Quarkus runs with the UBI image's
  options that return memory to the OS (`MaxHeapFreeRatio=20`, `GCTimeRatio=4`). With 200 MB of live
  connection state that leaves little room, so collections come more often: an inference, not a
  measured comparison. We did not tune the heap per core count or per connection count, on purpose;
  a bigger heap, or a collector without a card table, are open questions for the challenge.

**Repeatability** (the previous run). An earlier full run of the same matrix the same afternoon (not
kept) agreed within 3% at every connection count, e.g. Quarkus 133,823 and Kotlin 100,998 req/s at
1,024 connections. The previous run also started the fixed-rate phase and stopped it after 13 of its
48 steps, as a check that it works; those numbers are not part of these results.

## Realistic workload, 4 cores (1 rep)

Many sessions over the seed's people, each page load one of its person's rooms, while a writer outside
the apps commits presence and messages to the app's database (the design: [README](../README.md),
"Running"). Page loads per second offered (open model, 2,048 connections), with 60 s between a visible
tab's page loads, 50 s between its presence refreshes and 600 s between its messages: 1.2 presence
commits and 0.1 messages per page load, the same rows for every app.

| pages/s offered | visible tabs | served pages/s K / Q / R | µs CPU/page K / Q / R | p50 ms K / Q / R | p99 ms K / Q / R | RSS MB K / Q / R |
|---:|---:|---:|---:|---:|---:|---:|
| 333 | 19,980 | 333 / 333 / 333 | 4,628 / 4,572 / 2,933 | 4.6 / 4.3 / 3.3 | 17.2 / 32.2 / 4.0 | 688 / 496 / 266 |
| 833 | 49,980 | 742 / 832 / 833 | 4,878 / 4,311 / 3,342 | 1,183 / 18.9 / 3.4 | 2,315 / 58.7 / 7.9 | 695 / 663 / 293 |
| 1,667 | 100,020 | 668 / 1,623 / 1,079 | 5,411 / 2,416 / 3,341 | 4,899 / 919 / 2,919 | 5,235 / 1,627 / 3,808 | 704 / 615 / 397 |

- **At 333 pages/s every app keeps up, and Rust is the leanest**: least CPU per page, lowest p99 and
  memory. Quarkus' p99 is the highest of the three.
- **At 833 pages/s Kotlin falls behind** (742 served); Quarkus and Rust keep up, Rust with the lower
  latency.
- **At 1,667 pages/s no app keeps up**; Quarkus serves the most (1,623, Rust 1,079, Kotlin 668), with
  the least CPU per page. Latency there is queueing: at that rate Kotlin's and Rust's requests waited
  25% of their response time for a free connection of the 2,048, Quarkus' 1.2% (the report's
  "Problems").
- Busy app CPUs at 1,667 pages/s (of 4): Kotlin 3.33, Quarkus 3.36, Rust 3.22. The load driver used at
  most 0.39 of its 8 CPUs in any step.
- The writer committed at its rate in every step, with no failed commit. No step had client errors,
  failed connections or error lines in an app's log; the system counted 2 TCP resets during Kotlin's
  833 pages/s step.
- **One rep**: we have no measure of the spread of these numbers.

## Where the time goes

CPU flame graphs at 1,024 connections, 4 cores, recorded with the previous design
(`./bench.sh profile --app ... --conns 1024`; open in a browser): [Quarkus](flamegraphs/quarkus-4c-1024conns-cpu.html), [Kotlin](flamegraphs/kotlin-4c-1024conns-cpu.html).
Each is 10 s of async-profiler at one sample per millisecond of CPU, with `-XX:+DebugNonSafepoints`
so that inlined code is attributed to the right frame: a frame's sample count is its CPU time in
milliseconds.

From the investigation, 2 cores, Quarkus at 1,024 and 16,384 connections: how young-GC card scanning
grows with the number of connections ([NOTES.md](../NOTES.md)):
[1,024](flamegraphs/quarkus-2c-1024conns-cpu.html), [16,384](flamegraphs/quarkus-2c-16384conns-cpu.html).

## Earlier measurements

[RESULTS.md](../RESULTS.md) keeps the measurements of the investigation (2 and 4 cores, earlier
designs, the previous harness with the verification loadgen and oha), with the data in [`data/`](data/).
