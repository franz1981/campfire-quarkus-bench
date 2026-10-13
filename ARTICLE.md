# The original article

[**Kotlin keeps up with Rust**](https://www.wecodefire.com/p/kotlin-keeps-up-with-rust) (wecodefire.com),
on the author's [Kotlin port](https://github.com/mysticcoders/once-campfire-kotlin) of 37signals'
Campfire, compared with the [Rust port](https://github.com/basecamp/once-campfire-rust) from DHH's
benchmark table. Read the article itself; below is only a summary of its method and published
numbers, for comparison with this repository's runs. It was taken from the page with a summarizing
tool during this work, not transcribed by hand: check against the article before quoting it.

## Method

- Only the room page (`GET /rooms/:id`); the Kotlin port serves that route.
- SQLite, the same seed for every app; Kotlin calls SQLite through Java's FFM API, built from the
  same amalgamation and flags as the Rust build.
- A fragment cache with Rails' keys; in round two, a finished-response cache valid until SQLite's
  `data_version` changes or 15 s pass.
- The shared loadgen of [once-campfire-verification](https://github.com/basecamp/once-campfire-verification)
  with an exact-body contract (every response checked byte for byte), closed loop, 1 to 4,096
  connections, 20 s warmup, 15 s per step, 3 reps in alternating order, medians; CPU per request
  from each container's cgroup; logging off.
- Hetzner Cloud dedicated vCPUs (AMD EPYC Milan, 2.0 GHz): the app on a ccx13 (one physical core,
  two SMT threads) or a ccx23 (two physical cores); the loadgen on a separate ccx23 over the private
  network. One app at a time, Docker with host networking.
- Rust: its production image, with the listen backlog patched to `somaxconn` (128 stalled at 4,096
  connections). Kotlin: `eclipse-temurin:25-jre` with ParallelGC, and a GraalVM 25 native image.

## Published results, round two (response cache), requests/s

One core (ccx13):

| conns | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 3,787 | 4,411 | 4,264 |
| 16 | 18,614 | 19,175 | 16,201 |
| 64 | 18,634 | 21,643 | 16,993 |
| 256 | 19,155 | 24,049 | 18,431 |
| 1,024 | 15,686 | 21,377 | 16,419 |

Two cores (ccx23; stops at 256 connections because of packet loss on the virtual network):

| conns | Rust | Kotlin JVM | Kotlin native |
|---:|---:|---:|---:|
| 1 | 4,337 | 4,318 | 3,876 |
| 16 | 25,505 | 28,701 | 24,557 |
| 64 | 30,045 | 38,057 | 36,106 |
| 256 | 30,154 | 35,581 | 34,746 |

## What this repository changes

The same apps, page and data, measured on one machine instead of two cloud servers, with Hyperfoil
instead of the loadgen and oha: every difference and the reason for it is in
[DIFFERENCES.md](DIFFERENCES.md). Absolute numbers are therefore not comparable with the article's;
the ratios between apps are what this repository reports.
