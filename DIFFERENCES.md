# How this benchmark differs from DHH's and from the Kotlin article's

Three runs of "the room page benchmark" exist:

- **DHH's**: the numbers in the Rust port's README (Rust 106,494 req/s, Rails 4,101), measured with the
  [verification repo](https://github.com/basecamp/once-campfire-verification)'s own harness
  (`bin/benchmark`, `bench/compare.rb`), and his earlier posts on X made with an older load generator.
- **The Kotlin article's**: [Kotlin keeps up with Rust](https://www.wecodefire.com/p/kotlin-keeps-up-with-rust),
  with the Kotlin port's `bench/cloud/remote-bench.sh` ([ARTICLE.md](ARTICLE.md)).
- **This repository's**: `bench.sh`.

None of them is a copy of another. This table lists every difference we found and what we chose. Sources
are files in the three repositories at the commits in `upstream/`, and the verification repo at ec02deb.

| | DHH | Kotlin article | This repository | Why |
|---|---|---|---|---|
| Page | `GET /rooms/<watercooler>` | `GET /rooms/486777696` | same | The same room: 486777696 is the watercooler's id in the seed. |
| Data | the verification repo's seed (`bin/seed`) | the Rust port's parity seed | the Rust port's parity seed, committed (`fixtures/`) | The two seed scripts are identical apart from credentials; the parity one has a fixed secret key, so the session cookie can be fixed too. |
| Session | a fresh login as David before each round | a fixed cookie | a fixed cookie, from the Rust port's test vectors | No login flow needed. Effect: the first request updates the session's last activity, then not again for an hour, in every app. |
| Request | host, cookie, `accept-encoding: gzip` | the same; plus `user-agent: curl/8.7.1` in the fixed-rate phase | `accept: */*`, `user-agent: curl/8.7.1`, cookie, `accept-encoding: gzip`, in every phase | One request everywhere, the one the article's fixed-rate phase sent. |
| Load tool | the verification loadgen | the loadgen (all out) and oha (fixed rates) | Hyperfoil, both phases | One tool, run through jbang. In the open model it times each request from when it was due, and it spaces arrivals evenly (oha releases them a millisecond's worth at a time). |
| Connections | 16 | 1 to 4,096 | 1 to 4,096 (`article`), up to 16,384 (`many-connections`) | The article's range shows where each app saturates and how it degrades. |
| Durations | 2 s warmup at 4 connections, then 8 s; 3 alternating rounds, median | 20 s warmup at 16 connections; 15 s steps; 3 reps, alternating, median | the article's, plus 5 s of Hyperfoil warmup before each step | Each step's connections open, and Hyperfoil's own JVM warms, outside the measured window. |
| Fixed rates | none | 0.5 to 1.4 × the best app's rate at 64 connections, 2,048 connections | the rates you choose (`--rate`), 2,048 connections | Which load to look at is the reader's choice, not the harness'. |
| Checking responses | every response against the repo's route contract (message ids, words, DOM ids, gzip); one invalid response aborts | every response compared with the Rust page, byte for byte | `parity` (43 requests against Rust) and, after every app start, 100 requests compared with the Rust page byte for byte, aborting on a difference; measured responses checked for status | Hyperfoil cannot compare a 416 KB body; the smoke test proves each app serves the right page right before it is measured. |
| Request logging | **on** (the Rust port's default) | off | off | **To decide.** Off measures the same work in every app without a logger in the way; on is what DHH ran. Not verified that the three apps log the same amount when on. |
| App settings | `WEB_CONCURRENCY=3`, `JOB_CONCURRENCY=3`, `RAILS_MAX_THREADS=5` | the parity `.env.reference` (`JOB_CONCURRENCY=1`) | the parity `.env.reference` | The fixed environment the seed and cookie belong to. Effect of the job setting: not measured. |
| Rust build | the production Docker image, unmodified | the image plus `rust-backlog.patch` (listen backlog `somaxconn` instead of 128), disclosed | `cargo build --release`, unmodified | Nothing in this repository changes another port's code. The article reports that the unpatched Rust stalls at 4,096 connections. |
| Indexes | Rust adds two to the Rails schema at boot: messages by (room_id, created_at) and (room_id, updated_at) (`crates/db/src/schema.rs`, since `aa3b894` and `57a44a9`) | Rust: yes; Kotlin: no (it ports the queries, not the boot-time additions) | Rust and Quarkus: yes, created at boot; Kotlin: no, as published | Without the first, every room page that isn't served from the response cache sorts the room's whole history (32.7 µs vs 9.0 µs per query on the seed, NOTES.md "SQLite cost model", data in `work/readbench/result-index.txt`). Cache hits don't run that query. |
| Kotlin modules in the Quarkus app | n/a | n/a | from a fork, [franz1981/once-campfire-kotlin@6cc0ae8](https://github.com/franz1981/once-campfire-kotlin/commit/6cc0ae8) (on the measured `87436f3`), built only for the Quarkus app; the Kotlin app runs the published port | The fork pools the modules' per-thread objects (HMAC, SHA-256, a key buffer, Jackson's buffer recyclers) on virtual threads, where thread-locals would create one per request; output unchanged (parity, the fork's tests). |
| Realistic workload | none (one session, one page) | none | `--matrix realistic`: sessions over the seed's 5 people, page loads by visible tabs at a fixed rate, presence and message commits by a writer outside the apps | Every app gets the same rows written; writes inside the apps and WebSockets are not compared (only Rust has them). |
| Machine | Ryzen AI MAX+ 395; Docker `--cpuset-cpus 8-11`, loadgen on 12-15, same host; database on tmpfs | Hetzner Cloud: the app on a 1- or 2-core VM, the loadgen on another VM over the network | your machine: the app pinned to N physical cores (one SMT thread each), the driver on 2N others, loopback, no containers; database on disk | Runs anywhere with Linux, without Docker. |
| Metric | valid responses / time, median of rounds | req/s, CPU per request from the container's cgroup, medians | responses / time, CPU per request from the app's process (`/proc`), memory, latency, medians | |

The loadgen this repository used before Hyperfoil parsed its 1.87 MB contract once per connection inside
the measured window (about 7.5 ms each), so its 1,024- and 4,096-connection steps started late. That also
affected the article's numbers at those connection counts; DHH's runs (16 connections, the route
contract) were barely affected. This repository no longer uses it.
