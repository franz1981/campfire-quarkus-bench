# Results (Ryzen 9 7950X, all CPUs fixed at 4300 MHz, boost off)

Every response validated byte for byte against Rust's page by the verification loadgen (closed loop,
keep-alive). Medians of 3 interleaved reps (alternating order), 20 s warmup, 15 s per step. CPU per
request is the app cgroup's `cpu.stat`. Raw JSON and run configs in `results/data/`.

Apps: Rust (production build, `somaxconn` backlog patch, as in the article), Kotlin (Temurin 25,
`-XX:+UseParallelGC`, as published), Quarkus 4.0.0.Beta1 on Temurin 27 with the UBI image's JVM
options plus `-XX:TrimNativeHeapInterval=5000`, event loops = app CPUs + 1.
Quarkus: the staged architecture (README.md), SQLite through FFM (`Sqlite.java`), the cached page
written from a Netty direct buffer, the signed session cookie verified by a Java fast path
(`SignedCookies.java`), Vert.x strict thread mode on (`StrictThreadMode.java`). "anon MB" is the
app cgroup's anonymous memory at the end of each step.

The "Current" closed-loop tables were all measured with the loadgen patched to parse its contract
once (a patch in that harness; NOTES.md, "Measured, outside the apps"), all three
apps in the same run. Earlier tables used the unpatched loadgen: their 1024-connection rows had
connections still opening during the first ~7.7 s of each 15 s step (1.9 s at 256). Every step
also records the connections opened (`TcpActiveOpens`), the TCP error counters and new error
lines in the app log; all were clean unless stated.

## Current: 2 physical cores (headline)

App on CPUs 2,3, loadgen on 4-7, NUMA node 0 (`results/data/final4-2c`, all three apps in one run).
3 reps, 0 invalid, 0 errors, every requested connection opened. At 16384 connections the kernel
counted listen-queue overflows (one SYN retry each, all connections then opened): Rust 977-2,501
per step (backlog `somaxconn` 4096), Kotlin 670 in one rep (backlog 1024), Quarkus 0 (1024).

| conns | Kotlin | Quarkus 4 | Rust | Q4 / Kotlin | µs/req K / Q4 / R | p99 ms K / Q4 / R | anon MB K / Q4 / R |
|---:|---:|---:|---:|---:|---|---|---|
| 1 | 15,530 | 18,234 | 10,490 | 117% | 50.6 / 41.9 / 91.4 | 0.1 / 0.1 / 0.1 | 173 / **154** / 20 |
| 16 | 40,900 | 72,316 | 29,478 | 177% | 43.3 / 24.2 / 60.1 | 1.4 / 0.5 / 0.9 | 174 / 221 / 20 |
| 64 | 43,239 | 83,068 | 32,244 | 192% | 40.0 / 20.6 / 54.8 | 3.9 / 3.7 / 3.2 | 175 / 238 / 24 |
| 256 | 43,847 | 84,435 | 33,154 | 193% | 38.8 / 20.4 / 53.4 | 9.6 / 7.6 / 12.5 | 194 / 239 / 32 |
| 1024 | 42,364 | 76,092 | 27,926 | 180% | 40.1 / 22.7 / 63.4 | 31.4 / 25.4 / 137.2 | 306 / **243** / 71 |
| 4096 | 40,608 | 60,326 | 27,421 | 149% | 41.2 / 28.0 / 64.9 | 122.2 / 103.4 / 718.3 | 326 / 355 / 214 |
| 16384 | 36,318 | 48,451 | 25,688 | 133% | 46.1 / 34.5 / 68.7 | 977.4 / 960.5 / 3035.1 | 537 / **502** / 770 |

From 1024 to 16384 connections all three lose throughput and gain CPU per request: Rust -8%,
Kotlin -14%, Quarkus -36% (NOTES.md, "Many connections").

**Two users at once** (David and Jason, each validated against their own page, half the
connections each, 1 rep): Quarkus 69,216 req/s at 16 connections and 72,754 at 1024, 0 invalid
(Kotlin earlier: 40.9-41.3k / 42.6-43.7k). No result depends on every client sharing one session.

## Current: 4 physical cores

App on CPUs 0-3 (node 0); the loadgen on node 1 (CPUs 8-15, 8 workers), since 4 CPUs of node 0
saturated it at 1024 connections (`results/data/final4-4c`, all three apps in one run). 3 reps, 0
invalid, 0 errors, every requested connection opened, no listen-queue overflows.

| conns | Kotlin | Quarkus 4 | Rust | Q4 / Kotlin | µs/req K / Q4 / R | p99 ms K / Q4 / R | anon MB K / Q4 / R |
|---:|---:|---:|---:|---:|---|---|---|
| 1 | 14,782 | 16,034 | 9,726 | 108% | 49.0 / 45.5 / 94.4 | 0.1 / 0.1 / 0.1 | 196 / 207 / 23 |
| 16 | 49,254 | 101,626 | 45,516 | 206% | 57.6 / 29.8 / 73.4 | 0.8 / 0.3 / 0.7 | 197 / 269 / 23 |
| 64 | 70,002 | 118,053 | 48,550 | 169% | 41.8 / 25.6 / 67.9 | 2.0 / 3.1 / 2.5 | 217 / 286 / 28 |
| 256 | 95,758 | 130,084 | 53,562 | 136% | 32.6 / 23.7 / 60.7 | 6.2 / 5.9 / 8.3 | 292 / 292 / 39 |
| 1024 | 101,622 | 131,626 | 48,042 | 130% | 31.0 / 25.0 / 69.4 | 16.7 / 15.7 / 41.6 | 337 / **324** / 79 |
| 4096 | 90,360 | 115,934 | 44,199 | 128% | 34.2 / 28.8 / 76.9 | 58.6 / 62.3 / 330.2 | 351 / 464 / 231 |
| 16384 | 82,698 | 89,585 | 42,087 | 108% | 39.2 / 37.3 / 80.0 | 412.9 / 389.9 / 1480.7 | 592 / **419** / 815 |

With the loadgen fixed, Kotlin's 1024-connection result rose from 91,182 (`final-4c-lg8`) to
101,622; Quarkus' from 132,260 to 131,626.

## Current: equal offered rates (open phase, 2 cores)

`oha` at fixed rates through 2048 connections, latency-corrected, 15 s per rate, 2 reps (the Kotlin
port's open phase; `results/data/open-2c`). Each rate opens its connections afresh, which puts a
connect burst at the start of every step for both apps.

| offered req/s | CPU µs/req Q4 / K | p50 ms Q4 / K | p99 ms Q4 / K | anon MB Q4 / K |
|---:|---|---|---|---|
| 10,000 | 40-42 / 61 | 0.20 / 0.32 | 20-35 / 22-45 | 243-252 / 171-185 |
| 20,000 | 26-27 / 51 | 0.29-0.31 / 0.59 | 2.5-2.8 / 11-28 | 223-227 / 209-212 |
| 30,000 | 25-27 / 47-48 | 0.47-0.51 / 0.86-0.91 | 5.5-6.3 / 18-19 | 228-235 / 236-258 |
| 40,000 | 25-26 / 42-43 | 0.65 / 8.7-59 | 31-55 / 76-122 | 263-271 / 323-328 |
| 55,000 | 28 / (beyond capacity) | 1.8 | 665-789 | 246-268 |

At the same load Quarkus uses 35-50% less CPU per request with a lower median latency throughout.
Memory is lower from 30k up and higher at 10k, the first step after a closed-loop warmup at 16
connections where Quarkus runs at ~70k req/s: its young generation grew there and shrinks slowly
afterwards (NOTES.md, "Memory").

## Earlier builds

### 2 physical cores, before the loadgen fix and strict thread mode (`final3-2c`)

App on CPUs 2,3, loadgen on 4-7, NUMA node 0 (`results/data/final3-2c`; Kotlin and Rust from
`results/data/final-2c-staged-noshare`). 3 reps, 0 invalid, 0 errors.

| conns | Kotlin | Quarkus 4 | Rust | Q4 / Kotlin | µs/req K / Q4 / R | p99 ms K / Q4 / R | anon MB K / Q4 / R |
|---:|---:|---:|---:|---:|---|---|---|
| 1 | 15,333 | 18,336 | 10,468 | 120% | 49.9 / 42.0 / 91.4 | 0.1 / 0.1 / 0.1 | 178 / **157** / 19 |
| 16 | 40,362 | 73,444 | 29,533 | 182% | 43.5 / 23.8 / 59.9 | 1.4 / 0.5 / 0.9 | 179 / 220 / 19 |
| 64 | 43,124 | 84,832 | 32,715 | 197% | 40.1 / 20.4 / 54.1 | 4.0 / 3.6 / 3.1 | 180 / 238 / 23 |
| 256 | 43,537 | 84,364 | 34,370 | 194% | 39.1 / 20.4 / 51.7 | 9.3 / 7.7 / 11.0 | 194 / 242 / 31 |
| 1024 | 42,613 | 73,981 | 29,347 | 174% | 40.0 / 23.4 / 60.3 | 29.3 / 23.7 / 130.4 | 295 / **254** / 70 |

### 4 physical cores, before the loadgen fix and strict thread mode (`final3-4c`)

App on CPUs 0-3 (node 0); the loadgen on node 1 (CPUs 8-15, 8 workers), since 4 CPUs of node 0
saturated it at 1024 connections (`results/data/final3-4c`; Kotlin from `results/data/final-4c-lg8`,
same placement). 3 reps, 0 invalid, 0 errors. Rust wasn't re-run in this placement.

| conns | Kotlin | Quarkus 4 | Q4 / Kotlin | µs/req K / Q4 | p99 ms K / Q4 | anon MB K / Q4 |
|---:|---:|---:|---:|---|---|---|
| 1 | 14,639 | 16,262 | 111% | 49.3 / 43.7 | 0.1 / 0.1 | 206 / **204** |
| 16 | 49,373 | 103,100 | 209% | 57.4 / 29.1 | 0.7 / 0.3 | 208 / 267 |
| 64 | 70,490 | 121,296 | 172% | 41.7 / 24.5 | 1.9 / 3.1 | 221 / 285 |
| 256 | 93,706 | 129,514 | 138% | 33.2 / 23.6 | 6.0 / 5.9 | 274 / 290 |
| 1024 | 91,182 | 132,260 | 145% | 32.2 / 24.1 | 15.6 / 14.2 | 338 / **322** |

### 2 physical cores, staged architecture, sqlite-jdbc

App on CPUs 2,3 (one SMT thread per core), loadgen on 4-7, all on NUMA node 0. Quarkus 4: SQLite on
one stage thread (one read transaction and two `data_version` reads per batch, each request's own
auth reads), 3 event loops, released sqlite-jdbc. 3 interleaved reps, 0 invalid, 0 errors.

| conns | Kotlin | Quarkus 4 | Rust | Q4 / Kotlin | µs/req Kotlin / Q4 / Rust | p99 ms Kotlin / Q4 / Rust |
|---:|---:|---:|---:|---:|---|---|
| 1 | 15,333 | 14,031 | 10,468 | 92% | 49.9 / 58.8 / 91.4 | 0.1 / 0.1 / 0.1 |
| 16 | 40,362 | 42,561 | 29,533 | 105% | 43.5 / 36.3 / 59.9 | 1.4 / 0.7 / 0.9 |
| 64 | 43,124 | 48,875 | 32,715 | 113% | 40.1 / 30.0 / 54.1 | 4.0 / 7.2 / 3.1 |
| 256 | 43,537 | 52,826 | 34,370 | 121% | 39.1 / 28.5 / 51.7 | 9.3 / 11.2 / 11.0 |
| 1024 | 42,613 | 53,085 | 29,347 | 125% | 40.0 / 31.5 / 60.3 | 29.3 / 29.6 / 130.4 |

**Two users at once** (David and Jason, each validated against their own page, half the connections
each; 2 reps): Kotlin 40.9–41.3k / 42.6–43.7k req/s, Quarkus 4 staged 43.1–43.3k / 52.8–53.6k, the
previous Quarkus design (handler on 4 workers) 33.5–33.9k / 45.7–46.0k, at 16 / 1024 connections.
The staged result doesn't depend on every client sharing one session.

**Same-session read sharing** (identical auth reads in a batch run once; effective only because the
benchmark's clients share one session, so not the headline): 53.3k / 65.3k / 66.3k / 59.5k req/s at
16 / 64 / 256 / 1024 connections.

### 4 physical cores, staged architecture, sqlite-jdbc

App on CPUs 0-3 (one SMT thread per core), loadgen on 4-7, all on NUMA node 0 (`results/data/final-4c`).
Quarkus 4: 5 event loops, one SQLite stage thread. 3 interleaved reps, 0 invalid, 0 errors; the
loadgen used at most 2.71 of its 4 CPUs.

| conns | Kotlin | Quarkus 4 | Rust | Q4 / Kotlin | µs/req Kotlin / Q4 / Rust | p99 ms Kotlin / Q4 / Rust |
|---:|---:|---:|---:|---:|---|---|
| 1 | 15,754 | 14,521 | 10,439 | 92% | 49.5 / 55.6 / 91.7 | 0.1 / 0.1 / 0.1 |
| 16 | 48,873 | 83,501 | 47,230 | 171% | 58.8 / 35.6 / 70.8 | 0.7 / 0.5 / 0.6 |
| 64 | 71,163 | 99,796 | 50,321 | 140% | 42.3 / 30.0 / 65.7 | 2.0 / 1.4 / 2.4 |
| 256 | 91,995 | 99,742 | 55,488 | 108% | 34.6 / 29.5 / 58.8 | 6.1 / 5.0 / 8.0 |
| 1024 | 89,232 | 90,427 | 49,020 | 101% | 35.7 / 32.5 / 68.6 | 16.6 / 19.0 / 38.0 |

Quarkus stops scaling at about 100k req/s with the app using about 3 of its 4 cores. Per thread at
1024 connections (`scripts/thread-cpu.sh`, schedstat): the SQLite stage thread is on CPU 82% of the
time, runnable but waiting for a CPU 13%, parked 5%; the 5 event loops 41% / 18% / 41%. The single
SQLite thread is the limit (NOTES.md).

### SQLite through FFM (experiment, `campfire.sqlite=ffm`; 1 JVM, 3 windows, 2 interleaved reps)

The SQLite thread's reads and writes through the Kotlin port's FFM bindings and SQLite build
(`FfmReads`) instead of sqlite-jdbc; same statements, same batches, parity 43 checks / 0 different,
one SQLite library in the process (checked in `/proc/PID/maps`). Run variant `quarkus4-ffm`.

| req/s | JDBC (released driver) | FFM | patched JDBC driver |
|---|---|---|---|
| 4 cores, 16 conns | 79.4–85.1k | 110.6–115.1k | |
| 4 cores, 1024 conns | 95.0–96.8k | 129.1–134.7k | 118.3–119.1k (1 rep) |
| 2 cores, 16 conns | 41.2–45.0k | 59.1–62.3k | |
| 2 cores, 1024 conns | 55.0–57.2k | 65.5–69.0k | |

Event loops with FFM (1 rep): 4 cores, 3 / 5 loops: 130.0–133.7k / 113.5–118.3k at 16 connections,
115.9–117.3k / 131.1–134.3k at 1024; 2 cores, 2 / 3 loops: 59.5–64.3k / 52.4–56.5k at 16,
61.6–66.6k / 66.4–67.1k at 1024. With JDBC at 4 cores, 3 / 4 / 5 loops: 109.2–112.5k / 95.2–97.4k /
about 90–97k at 1024. No count is best at every concurrency; the default stays app CPUs + 1.

### 2 physical cores, handler on a worker pool (previous design)

App on CPUs 2,3 (one SMT thread per core), loadgen on 4-7, all on NUMA node 0. Quarkus: 2 event
loops, 4 workers.

| conns | kotlin | quarkus4-stock | rust | µs/req kotlin / quarkus4-stock / rust | p99 ms kotlin / quarkus4-stock / rust |
|---:|---:|---:|---:|---|---|
| 1 | 16,680 | 14,269 | 10,439 | 45.3 / 58.2 / 91.6 | 0.1 / 0.1 / 0.1 |
| 16 | 42,579 | 33,481 | 29,397 | 39.8 / 48.2 / 60.2 | 1.0 / 3.5 / 0.9 |
| 64 | 44,922 | 39,415 | 32,634 | 37.2 / 40.7 / 54.4 | 3.4 / 4.9 / 3.1 |
| 256 | 45,527 | 45,989 | 33,976 | 37.3 / 36.5 / 52.4 | 8.4 / 11.1 / 10.7 |
| 1024 | 43,484 | 44,023 | 29,076 | 38.4 / 39.0 / 61.2 | 28.5 / 33.2 / 130.2 |

45 measurements, invalid responses + errors: 0.

Quarkus 4 vs Kotlin: 86% / 79% / 88% / 101% / 101% at 1 / 16 / 64 / 256 / 1024 connections; vs Rust
115–151%. The loadgen stayed below 80% of its CPUs in every step.

### 2 cores with SMT (Hetzner ccx23-like: 2 cores, 4 threads)

App on CPUs 2,3,18,19, loadgen on 8-15,24-31 (NUMA node 1). Quarkus: default event loops (4), 8 workers.

| conns | kotlin | quarkus4-stock | rust | µs/req kotlin / quarkus4-stock / rust | p99 ms kotlin / quarkus4-stock / rust |
|---:|---:|---:|---:|---|---|
| 1 | 12,116 | 11,367 | 9,000 | 65.0 / 74.7 / 106.8 | 0.1 / 0.1 / 0.1 |
| 16 | 36,995 | 34,019 | 34,037 | 84.9 / 92.2 / 99.7 | 0.8 / 1.4 / 0.9 |
| 64 | 51,467 | 44,905 | 35,298 | 62.7 / 72.1 / 95.7 | 2.6 / 3.2 / 3.4 |
| 256 | 62,125 | 55,407 | 37,221 | 53.8 / 60.8 / 90.3 | 8.1 / 9.7 / 11.9 |
| 1024 | 63,160 | 56,693 | 35,415 | 53.3 / 59.8 / 95.6 | 21.8 / 27.2 / 45.1 |

45 measurements, invalid responses + errors: 0.

### Experiments (2 physical cores, 1024 connections unless stated; 1 JVM, 3 windows)

| Quarkus 4 configuration | req/s | µs/req |
|---|---|---|
| handler on the event loops (no offload), 2 cores with SMT | 42.0–42.7k | 82.6–84.4 |
| offload, event loops 4, workers 1 / 2 / 4 / 8 | 28.3–29.0k / 31.5–32.6k / 45.6–46.7k / 46.2–47.0k | 50.4–53.1 / 49.2–51.8 / 37.0–38.3 / 36.7–37.9 |
| offload, event loops 1, workers 1 / 2 / 3 / 4 | 29.6–30.2k / 33.6–33.9k / 41.3–42.4k / 43.1–43.9k | 48.3–50.7 / 47.8–48.4 / 39.2–40.3 / 37.0–38.3 |
| **offload, event loops 2, workers 4** | **47.2–48.0k** | **36.2–37.0** |
| same, `ReentrantLock` instead of `synchronized` on `data_version` | 35.2–36.4k | 43.5–45.5 |
| Kotlin, same session | 44.8–45.5k | 36.5–37.1 |

Per thread (cpu-clock samples and `/proc`, 1024 connections): Kotlin's workers use 1.02 cores
(22.8 µs/req) and its event loops 0.64 (14.2); Quarkus' 4 workers 1.12 (25.8) and 2 event loops 0.70
(16.3). Event loops spend 65–85% of their samples in the kernel (socket send/receive).

### 1 core (2 SMT threads), before the offload design

Quarkus 4 with the handler on the event loops, against Rust/Kotlin measured earlier in the same
setup (data not kept): 104–142% of Rust, 84–108% of Kotlin. Superseded by the 2-core results.
