#!/usr/bin/env bash
# The Campfire room-page benchmark: Quarkus vs the Kotlin and Rust ports (README.md).
#
#   ./bench.sh setup                         check requirements, fetch the ports, build everything
#   ./bench.sh parity [quarkus|kotlin]       compare every response with the Rust port's
#   ./bench.sh run [options]                 measure, then print the results
#   ./bench.sh report RUN_DIR [--markdown]   print a run's results again
#   ./bench.sh profile --app APP [options]   a flame graph of one app under load
#
# run / profile options:
#   --matrix NAME          bench/matrix/NAME.conf (default: article)
#   --cores N              app cores (one SMT thread each); the CPUs are picked for you (default: the matrix's)
#   --app-cpus LIST        or pick them yourself: the app's CPUs (taskset list, e.g. 1-4) ...
#   --driver-cpus LIST     ... and the load driver's (Hyperfoil), never on the app's cores (e.g. 8-15)
#   --apps a,b             quarkus, kotlin, rust (default: the matrix's)
#   --conns a,b            all-out connection counts (default: the matrix's; profile: 1024)
#                          profile: the load of a run's step: --matrix realistic at its first rate (with the
#                          writer), else the first --rate, else all out at the first --conns
#   --reps N  --secs N     repetitions and seconds per step (default: the matrix's)
#   --rate R,R...          fixed rates instead of all out: each app at each of these req/s, in turn
#                          (matrix realistic: page loads/s, with the writes that go with them; see below)
#   --rate-conns N         connections the fixed rates are driven through (default: the matrix's, 2048)
#   --page-every S --presence-every S --message-every S --sessions N --message-room ID|all
#                          (matrix realistic) a visible tab loads a room page every PAGE_EVERY s, refreshes its
#                          presence every PRESENCE_EVERY s and posts a message every MESSAGE_EVERY s: per page
#                          load, PAGE_EVERY/PRESENCE_EVERY presence commits and PAGE_EVERY/MESSAGE_EVERY messages
#   --force                measure even if the preflight finds a problem
#   --app A                (profile) the app: quarkus or kotlin
#   --event E[,E...]       (profile) async-profiler events: cpu (default), alloc, wall, lock; several need --format jfr
#   --format F             (profile) html (flame graph, default), jfr (for JFR tools and the Jafar MCP server), collapsed
# Environment: JDK_KOTLIN, JDK_QUARKUS (SDKMAN identifiers), BENCH_LOCK (the lock file runs share),
#   AP_OPTS (more async-profiler options for profile, e.g. "-i 100us"), APP_JVM_OPTS (more options for both JVM apps),
#   CAMPFIRE_FRAGMENT_MEMORY=heap|native and CAMPFIRE_DEFLATE=ffm|jdk (the Quarkus app's switches, README "After").
set -euo pipefail
export LC_ALL=C
ROOT=$(cd "$(dirname "$0")" && pwd)

# ---- what is measured ------------------------------------------------------------------------
JDK_KOTLIN=${JDK_KOTLIN:-25.0.3-tem}     # the Kotlin port as published
JDK_QUARKUS=${JDK_QUARKUS:-27.0.0-tem}   # Quarkus: build and run
PORT=4390 ROOM=486777696
RUST=$ROOT/upstream/once-campfire-rust KOTLIN=$ROOT/upstream/once-campfire-kotlin
# The Kotlin port's modules as the Quarkus app uses them: a fork whose per-thread objects are pooled on
# virtual threads (DIFFERENCES.md); the Kotlin app itself always runs from $KOTLIN, as published.
KOTLIN_POOLED=$ROOT/upstream/once-campfire-kotlin-pooled
WORK=$ROOT/target                        # app run directories, build logs (not committed)
COOKIE=$(cat "$ROOT/fixtures/cookie.txt")

# Both JVMs: ParallelGC (the Kotlin port's published JAVA_OPTS), compact headers, a 512 MB heap ceiling.
JVM_COMMON=(-XX:+UseParallelGC -XX:+UseCompactObjectHeaders -Xmx512m --enable-native-access=ALL-UNNAMED)
# shellcheck disable=SC2206  # APP_JVM_OPTS: words on purpose (diagnostics, e.g. a GC log; config.txt records them)
JVM_COMMON+=(${APP_JVM_OPTS:-})
# Quarkus: the options of Red Hat's UBI OpenJDK images (memory given back to the OS sooner), glibc's
# heap trimmed every 5 s, Netty's and Vert.x' checks off. Event loops: Quarkus' default.
JVM_QUARKUS=(-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90 -XX:+ExitOnOutOfMemoryError
  -XX:TrimNativeHeapInterval=5000 -Dvertx.disableHttpHeadersValidation=true -Dio.netty.leakDetection.level=disabled
  -Dio.netty.allocator.lowMemory=false -Dio.netty.buffer.checkBounds=false -Dio.netty.buffer.checkAccessible=false
  -Dio.netty.tryReflectionSetAccessible=true --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow)

log() { echo "[$(date +%T)] $*" >&2; }
die() { echo "error: $*" >&2; exit 1; }
usage() { sed -n '2,/^set -e/p' "$0" | sed 's/^# \{0,1\}//; /^set -e/d'; }

jdk() {  # JAVA_HOME of an SDKMAN JDK, or how to install it
  local home=${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$1
  [ -x "$home/bin/java" ] || die "JDK $1 not found: sdk install java $1"
  echo "$home"
}

lock() {  # one measurement at a time on this machine
  exec 9> "${BENCH_LOCK:-${XDG_RUNTIME_DIR:-/tmp}/campfire-bench.lock}"
  flock -n 9 || { log "waiting for another measurement to finish (${BENCH_LOCK:-the lock})"; flock 9; }
}

# ---- setup -----------------------------------------------------------------------------------
cmd_setup() {
  local missing=() tool
  for tool in git cargo cc jbang taskset lscpu flock curl cmp diff nstat; do command -v "$tool" > /dev/null || missing+=("$tool"); done
  [ ${#missing[@]} = 0 ] || die "missing: ${missing[*]} (README.md, Requirements)"
  local jk jq; jk=$(jdk "$JDK_KOTLIN"); jq=$(jdk "$JDK_QUARKUS")
  cd "$ROOT"

  log "the ports at the measured commits (git submodules)"
  git submodule update --init upstream/once-campfire-rust upstream/once-campfire-kotlin upstream/once-campfire-kotlin-pooled
  git -C "$KOTLIN" submodule update --init --depth 1 reference
  git -C "$KOTLIN_POOLED" submodule update --init --depth 1 reference

  mkdir -p "$WORK"
  log "building the Rust port, as published ($WORK/build-rust.log)"
  (cd "$RUST" && cargo build --release -p campfire) > "$WORK/build-rust.log" 2>&1 || die "Rust build failed: $WORK/build-rust.log"

  log "building the Kotlin port"
  # Its SQLite library is built from the amalgamation the Rust build just fetched into cargo's registry.
  local src; src=$(ls -d "${CARGO_HOME:-$HOME/.cargo}"/registry/src/*/libsqlite3-sys-*/sqlite3 2> /dev/null | tail -1)
  [ -n "$src" ] || die "SQLite sources not found in cargo's registry (CARGO_HOME?)"
  SQLITE_SRC=$src "$KOTLIN/native/build.sh" > "$WORK/build-kotlin-native.log" 2>&1 || die "Kotlin native build failed: $WORK/build-kotlin-native.log"
  # No Gradle or Kotlin daemon is left running afterwards: it would use CPU during the measurements.
  (cd "$KOTLIN" && JAVA_HOME=$jk ./gradlew -q --no-daemon -Pkotlin.compiler.execution.strategy=in-process :campfire:installDist) \
    > "$WORK/build-kotlin.log" 2>&1 || die "Kotlin build failed: $WORK/build-kotlin.log"

  log "building the Kotlin port's modules for the Quarkus app (the fork in upstream/once-campfire-kotlin-pooled)"
  (cd "$KOTLIN_POOLED" && JAVA_HOME=$jk ./gradlew -q --no-daemon -Pkotlin.compiler.execution.strategy=in-process :campfire:installDist) \
    > "$WORK/build-kotlin-pooled.log" 2>&1 || die "Kotlin (pooled) build failed: $WORK/build-kotlin-pooled.log"

  log "building the Quarkus app (it uses the Kotlin port's Rails-format modules as jars)"
  local version m lib=$KOTLIN_POOLED/campfire/build/install/campfire/lib
  version=$(git -C "$KOTLIN_POOLED" rev-parse --short HEAD)
  : > "$WORK/build-quarkus.log"
  for m in ruby-compat rails-compat routes db kit assets richtext views campfire; do
    (cd quarkus4-app && JAVA_HOME=$jq ./mvnw -q -B install:install-file -Dfile="$lib/$m.jar" \
      -DgroupId=campfire.kotlin -DartifactId="$m" -Dversion="$version" -Dpackaging=jar -DgeneratePom=true) >> "$WORK/build-quarkus.log" 2>&1 \
      || die "installing the Kotlin port's $m.jar failed: $WORK/build-quarkus.log"
  done
  (cd quarkus4-app && JAVA_HOME=$jq ./mvnw -B package -Dcampfire.kotlin.version="$version") >> "$WORK/build-quarkus.log" 2>&1 \
    || die "Quarkus build failed: $WORK/build-quarkus.log"
  log "done; next: ./bench.sh parity, then ./bench.sh run"
}

# ---- apps ------------------------------------------------------------------------------------
APP_PID=""
start_app() {  # APP CPUS: on a fresh copy of the seed, pinned, until /up answers
  local app=$1 cpus=$2 dir=$WORK/run-$1-$PORT
  ! curl -fs -o /dev/null "http://127.0.0.1:$PORT/up" || die "something already answers on port $PORT"
  rm -rf "$dir"; mkdir -p "$dir/db" "$dir/files"
  cp "$SEED_DB" "$dir/db/production.sqlite3"
  local env=(RAILS_LOG_LEVEL=warn LOG_REQUESTS=false RAILS_MAX_THREADS=5 HTTP_PORT=$PORT TARGET_PORT=$((PORT + 1))
    CAMPFIRE_STORAGE_PATH="$dir" CAMPFIRE_DATABASE_PATH="$dir/db/production.sqlite3" CAMPFIRE_FILES_PATH="$dir/files")
  local cmd java
  case $app in
    rust) cmd=("$RUST/target/release/campfire") ;;
    kotlin) java=$(jdk "$JDK_KOTLIN")/bin/java
      cmd=("$java" "${JVM_COMMON[@]}" -cp "$KOTLIN/campfire/build/install/campfire/lib/*" campfire.app.MainKt) ;;
    quarkus) java=$(jdk "$JDK_QUARKUS")/bin/java
      env+=(CAMPFIRE_SQLITE_LIB="$KOTLIN/native/linux-x86_64/libcampfire_sqlite3.so")
      cmd=("$java" "${JVM_COMMON[@]}" "${JVM_QUARKUS[@]}" -jar "$ROOT/quarkus4-app/target/quarkus-app/quarkus-run.jar") ;;
    *) die "unknown app $app (quarkus, kotlin, rust)" ;;
  esac
  # The Rust port's parity environment (secret key, settings), then ours. 9>&-: the app never holds
  # the lock, even if it outlives this script.
  ( set -a; source "$RUST/parity/.env.reference"; set +a; exec env "${env[@]}" taskset -c "$cpus" "${cmd[@]}" ) > "$OUT/$app.log" 2>&1 9>&- &
  APP_PID=$!
  for _ in $(seq 1 600); do
    kill -0 "$APP_PID" 2> /dev/null || break
    curl -fs -o /dev/null "http://127.0.0.1:$PORT/up" && return 0
    sleep 0.05
  done
  tail -20 "$OUT/$app.log" >&2; die "$app didn't start"
}
SEED_DB=$ROOT/fixtures/seed.sqlite3  # the realistic workload swaps in its prepared copy
stop_app() {
  [ -n "$APP_PID" ] || return 0
  kill "$APP_PID" 2> /dev/null || true; wait "$APP_PID" 2> /dev/null || true; APP_PID=""
}
alive() { kill -0 "$APP_PID" 2> /dev/null; }
trap 'stop_writer; stop_app' EXIT

# ---- what the machine does -------------------------------------------------------------------
app_cpu_secs() { awk -v hz="$(getconf CLK_TCK)" '{ printf "%.3f", ($14 + $15) / hz }' "/proc/$APP_PID/stat"; }
app_mem() {  # the app's resident memory now (all, anonymous) and its peak since it started
  awk '$1 == "VmRSS:" { r = $2 } $1 == "RssAnon:" { a = $2 } $1 == "VmHWM:" { p = $2 }
    END { printf "rss_mb=%d\nrss_anon_mb=%d\nrss_peak_mb=%d\n", r / 1024, a / 1024, p / 1024 }' "/proc/$APP_PID/status"
}
cpus_busy() {  # busy seconds (user+nice+system+irq+softirq+steal) summed over a CPU list
  local want=" $(cpu_list "$1" | tr '\n' ' ')"
  awk -v want="$want" -v hz="$(getconf CLK_TCK)" '$1 ~ /^cpu[0-9]+$/ && index(want, " " substr($1, 4) " ") { t += $2 + $3 + $4 + $7 + $8 + $9 } END { printf "%.2f", t / hz }' /proc/stat
}
cpu_list() {  # 0-3,8 -> one CPU per line
  tr ',' '\n' <<< "$1" | awk -F- 'NF { for (c = $1; c <= ($2 == "" ? $1 : $2); c++) print c }'
}
siblings() {  # every hardware thread of the cores of a CPU list
  local c t; for c in $(cpu_list "$1"); do
    t=/sys/devices/system/cpu/cpu$c/topology; cat "$t/core_cpus_list" 2> /dev/null || cat "$t/thread_siblings_list"
  done | tr '\n' ',' | sed 's/,$//'
}
now() { date +%s.%N; }
log_errors() { grep -cE 'ERROR|Exception|panicked' "$OUT/$1.log" || true; }
tcp_counters() {  # system-wide, since the last `nstat -n`
  nstat -z TcpActiveOpens TcpAttemptFails TcpEstabResets TcpExtListenOverflows TcpExtTCPSynRetrans | awk '
    $1 == "TcpActiveOpens" { print "opens=" $2 } $1 == "TcpAttemptFails" { print "connect_failures=" $2 }
    $1 == "TcpEstabResets" { print "resets=" $2 } $1 == "TcpExtListenOverflows" { print "listen_overflows=" $2 }
    $1 == "TcpExtTCPSynRetrans" { print "syn_retransmits=" $2 }'
}

# ---- placement -------------------------------------------------------------------------------
# CORES physical cores for the app, one SMT thread each, on the NUMA node with the most cores (its
# first core left to the OS, which runs its housekeeping there), and twice as many for the load
# driver, on one node: the app's if they fit, else the largest other one, else what is left on the
# app's. Never a shared core, never a component across two nodes.
place() {
  local cores=$1 table node
  table=$(lscpu -p=CPU,CORE,NODE | grep -v '^#' | awk -F, '!seen[$2]++ { print ($3 == "" ? 0 : $3), $1 }')  # NODE FIRST-CPU per core
  node=$(cut -d' ' -f1 <<< "$table" | sort | uniq -c | sort -s -k1,1nr | awk 'NR == 1 { print $2 }')
  local cpus other=() n
  read -r -a cpus <<< "$(awk -v n="$node" '$1 == n { printf "%s ", $2 }' <<< "$table")"
  local skip=0; [ ${#cpus[@]} -gt "$cores" ] && skip=1
  local app=("${cpus[@]:$skip:$cores}") rest=("${cpus[@]:$((skip + cores))}")
  [ ${#app[@]} = "$cores" ] || die "no NUMA node has $cores cores; use --cores or --app-cpus and --driver-cpus"
  local lg=("${rest[@]:0:$((cores * 2))}")
  if [ ${#lg[@]} -lt $((cores * 2)) ]; then
    n=$(awk -v n="$node" '$1 != n { print $1 }' <<< "$table" | sort | uniq -c | sort -s -k1,1nr | awk 'NR == 1 { print $2 }')
    [ -n "$n" ] && read -r -a other <<< "$(awk -v n="$n" '$1 == n { printf "%s ", $2 }' <<< "$table")"
    [ ${#other[@]} -gt ${#lg[@]} ] && lg=("${other[@]:0:$((cores * 2))}")
  fi
  [ ${#lg[@]} -gt 0 ] || die "no core left for the load driver; use --cores or --app-cpus and --driver-cpus"
  [ ${#lg[@]} -ge $((cores * 2)) ] || log "note: the load driver gets ${#lg[@]} cores, not $((cores * 2)); watch the report for a saturated driver"
  APP_CPUS=$(IFS=,; echo "${app[*]}") DRIVER_CPUS=$(IFS=,; echo "${lg[*]}")
  SIDE_CPUS=""; [ "$skip" = 1 ] && SIDE_CPUS=$(siblings "${cpus[0]}")  # the core left to the OS (the workload's writer)
}

preflight() {  # placement, frequency, other load, file limits; refuses (unless --force) when the result would mislead
  local bad=0 c core
  declare -A owner=()
  for c in $(cpu_list "$APP_CPUS"); do core=$(siblings "$c"); [ -z "${owner[$core]:-}" ] || { log "app CPUs $APP_CPUS share core $core"; bad=1; }; owner[$core]=app; done
  for c in $(cpu_list "$DRIVER_CPUS"); do core=$(siblings "$c"); [ "${owner[$core]:-}" != app ] || { log "load driver CPU $c shares a core with the app"; bad=1; }; done
  local cpu0 gov boost; cpu0=$(cpu_list "$APP_CPUS" | head -1)
  gov=$(cat "/sys/devices/system/cpu/cpu$cpu0/cpufreq/scaling_governor" 2> /dev/null || echo unknown)
  boost=$(cat /sys/devices/system/cpu/cpufreq/boost 2> /dev/null || echo unknown)
  [ "$gov" = performance ] && [ "$boost" = 0 ] || log "note: governor $gov, boost $boost: for stable numbers fix the frequency (README.md)"
  local max=0 c; for c in ${CONNS:-} $RATE_CONNS; do [ "$c" -gt "$max" ] && max=$c; done
  [ "$(ulimit -n)" -gt $((max * 2 + 1000)) ] || { log "the open-file limit ($(ulimit -n)) is too low for $max connections on each side"; bad=1; }
  # Nothing else running on those cores (both threads of each): under 2.5% busy per app CPU and 4% per
  # driver CPU over 2 s, 5 tries.
  local app_all drv_all try a0 a1 l0 l1 app_busy drv_busy na nl quiet=0
  app_all=$(siblings "$APP_CPUS") drv_all=$(siblings "$DRIVER_CPUS")
  na=$(cpu_list "$app_all" | wc -l) nl=$(cpu_list "$drv_all" | wc -l)
  for try in 1 2 3 4 5; do
    a0=$(cpus_busy "$app_all"); l0=$(cpus_busy "$drv_all"); sleep 2; a1=$(cpus_busy "$app_all"); l1=$(cpus_busy "$drv_all")
    app_busy=$(awk -v a="$a0" -v b="$a1" -v n="$na" 'BEGIN { printf "%.3f", (b - a) / 2 / n }')
    drv_busy=$(awk -v a="$l0" -v b="$l1" -v n="$nl" 'BEGIN { printf "%.3f", (b - a) / 2 / n }')
    awk -v a="$app_busy" -v d="$drv_busy" 'BEGIN { exit !(a < 0.025 && d < 0.04) }' && { quiet=1; break; }
  done
  [ "$quiet" = 1 ] || { log "something else is using the cores (busy per CPU: app $app_busy, driver $drv_busy); top processes:"
    ps -eo pid,psr,pcpu,comm --sort=-pcpu | head -6 >&2; bad=1; }
  [ "$bad" = 0 ] || [ "$FORCE" = 1 ] || die "preflight failed (--force measures anyway)"
}

report() {  # UTF-8 whatever the locale: the tables use µ and the chart █
  local java; java=$(jdk "$JDK_QUARKUS")
  JAVA_HOME=$java jbang --quiet --java-options=-Dstdout.encoding=UTF-8 "$ROOT/bench/Report.java" "$@"
}

# ---- Hyperfoil -------------------------------------------------------------------------------
hyperfoil() {  # RUN_DIR ARGS: Hyperfoil 0.29.3 (jbang-catalog.json) on the driver CPUs, its run data under RUN_DIR
  local run=$1 java; shift; java=$(jdk "$JDK_KOTLIN")
  local args=(-PPORT="$PORT" -PROOM="$ROOM" -PCOOKIE="$COOKIE" -PTHREADS="$(cpu_list "$DRIVER_CPUS" | wc -l)" "$@")
  if [ "$WORKLOAD" = realistic ]; then  # a template's files are never read from disk (Hyperfoil asks to upload them)
    mkdir -p "$run"; render "${args[-1]}" "$run/scenario.hf.yaml" "${args[@]:0:${#args[@]}-1}"; args=("$run/scenario.hf.yaml")
  fi
  (cd "$ROOT" && JAVA_HOME=$java taskset -c "$DRIVER_CPUS" jbang --quiet --java-options=-Xmx4g \
    --java-options=-Dio.hyperfoil.rootdir="$run" --java-options=-Dio.hyperfoil.cpu.watchdog.idle.threshold=0.0 hyperfoil \
    "${args[@]}" < /dev/null)
}
render() {  # TEMPLATE OUT -PNAME=VALUE...: the template with every !param replaced (given value, else its default)
  local tpl=$1 out=$2 kv e=(); shift 2
  local v; for kv in "$@"; do  # the value escaped for sed's replacement (\, the | delimiter, &)
    kv=${kv#-P}; v=${kv#*=}; v=${v//\\/\\\\}; v=${v//|/\\|}; v=${v//&/\\&}
    e+=(-e "s|!param ${kv%%=*}( [^] ,]+)?([] ,]\|$)|$v\2|g")
  done
  sed -E "${e[@]}" -e 's/!param [A-Z_]+ ([^] ,]+)/\1/g' "$tpl" > "$out"
  ! grep -q '!param' "$out" || die "$out: a parameter without a value"
}
all_out() {  # RUN_DIR CONNS SECS: the closed-loop scenario, no warmup phase of its own
  hyperfoil "$1" -PCONNS="$2" -PUSERS="$2" -PWARMUP=1s -PDURATION="$3s" -PPAGES="$OUT/workload/pages.csv" "$ROOT/bench/$SCENARIO.hf.yaml"
}

# The request every load step sends (bench/*.hf.yaml), with curl: the decoded body to stdout.
fetch() {  # [COOKIE ROOM]: the room page as the load sends it, decoded
  curl -fsS --compressed -A curl/8.7.1 -H 'accept: */*' -H "cookie: ${1:-$COOKIE}" -H 'accept-encoding: gzip' "http://127.0.0.1:$PORT/rooms/${2:-$ROOM}"
}

# ---- the realistic workload (matrix realistic, bench/Workload.java) ---------------------------
# Many sessions over the seed's people, each request one of its person's rooms at random, while a
# writer outside the apps commits presence and messages to the app's database: the same rows for
# every app. Caches stay on.
workload() {  # jbang bench/Workload.java ARGS
  JAVA_HOME=$(jdk "$JDK_QUARKUS") jbang --quiet --java-options=--enable-native-access=ALL-UNNAMED "$ROOT/bench/Workload.java" "$@"
}
prepare_workload() {  # the run's database and pages; one page per (person, room) to check
  [ "$WORKLOAD" = realistic ] || return 0
  local secret; secret=$(sed -n 's/^SECRET_KEY_BASE=//p' "$RUST/parity/.env.reference")
  mkdir -p "$OUT/workload"
  workload prepare "$ROOT/fixtures/seed.sqlite3" "$OUT/workload/db.sqlite3" "$OUT/workload/pages.csv" "$SESSIONS" "$secret" 2> /dev/null \
    | tee "$OUT/workload/prepare.txt" >&2
  awk -F, '!seen[$2 "," $3]++' "$OUT/workload/pages.csv" > "$OUT/workload/checks.csv"
  SEED_DB=$OUT/workload/db.sqlite3
}
WRITER_PID=""
start_writer() {  # APP OUT_FILE PRESENCE_PER_SEC MESSAGES_PER_SEC: the writer on the app's database, on the OS's core
  [ "$WORKLOAD" = realistic ] || return 0
  local pin=(); [ -n "${SIDE_CPUS:-}" ] && pin=(taskset -c "$SIDE_CPUS")
  [ -n "${SIDE_CPUS:-}" ] || log "note: with --app-cpus/--driver-cpus the writer is not pinned (it may share their cores)"
  # jbang execs the JVM: WRITER_PID is the writer itself, and SIGTERM reaches its exit report
  JAVA_HOME=$(jdk "$JDK_QUARKUS") "${pin[@]}" jbang --quiet --java-options=--enable-native-access=ALL-UNNAMED "$ROOT/bench/Workload.java" \
    write "$WORK/run-$1-$PORT/db/production.sqlite3" "$3" "$4" "$MESSAGE_ROOM" > "$2" 2> "${2%.txt}.err" 9>&- &
  WRITER_PID=$!
}
stop_writer() {
  [ -n "$WRITER_PID" ] || return 0
  kill -TERM "$WRITER_PID" 2> /dev/null || true; wait "$WRITER_PID" 2> /dev/null || true; WRITER_PID=""
}

# The Rust port's page, fetched once per run: what every app must serve, byte for byte.
reference() {
  start_app rust "$APP_CPUS"
  fetch > "$OUT/expected.html" || die "Rust didn't serve the room page"
  if [ "$WORKLOAD" = realistic ]; then  # every (person, room) page of the workload
    mkdir -p "$OUT/expected"
    local n=0 cookie room person
    while IFS=, read -r cookie room person; do
      n=$((n + 1)); fetch "$cookie" "$room" > "$OUT/expected/$n.html" || die "Rust didn't serve room $room for person $person"
      [ "$(curl -s -o /dev/null -w '%{http_code}' -H "cookie: $cookie" "http://127.0.0.1:$PORT/rooms/$room")" = 200 ] \
        || die "Rust answered room $room for person $person with something other than its page (a redirect?)"
    done < "$OUT/workload/checks.csv"
    log "Rust: $n (person, room) pages of the workload"
  fi
  stop_app
}

# After every start: a smoke test (100 requests, each decoded body equal to the Rust page; a failure
# stops the run), then WARMUP seconds all out at 16 connections.
warm() {
  local app=$1 i
  for i in $(seq 1 100); do
    fetch | cmp -s - "$OUT/expected.html" || die "$app's room page differs from the Rust port's (request $i)"
  done
  log "$app: 100 of 100 room pages identical to the Rust port's"
  if [ "$WORKLOAD" = realistic ]; then
    local n=0 cookie room person
    while IFS=, read -r cookie room person; do
      n=$((n + 1)); fetch "$cookie" "$room" | cmp -s - "$OUT/expected/$n.html" || die "$app's room $room for person $person differs from the Rust port's"
    done < "$OUT/workload/checks.csv"
    log "$app: all $n (person, room) pages identical to the Rust port's"
  fi
  all_out "$OUT/warmup-$app" 16 "$WARMUP" > "$OUT/warmup-$app.out" 2>&1 || true
}

drive() {  # KIND RUN_DIR LOAD [RATE]: Hyperfoil's warmup phase (HF_WARMUP s) then SECS s, as a step runs them
  local kind=$1 run=$2 load=$3 rate=${4:-$3}
  case $kind in
    closed) hyperfoil "$run" -PCONNS="$load" -PUSERS="$load" -PDURATION="${SECS}s" -PWARMUP="${HF_WARMUP}s" -PPAGES="$OUT/workload/pages.csv" "$ROOT/bench/$SCENARIO.hf.yaml" ;;
    open|tabs) hyperfoil "$run" -PCONNS="$RATE_CONNS" -PRATE="$rate" -PSESSIONS="$(awk -v r="$rate" 'BEGIN { s = int(r * 2) + 1; print (s < 100 ? 100 : s) }')" \
            -PDURATION="${SECS}s" -PWARMUP="${HF_WARMUP}s" -PPAGES="$OUT/workload/pages.csv" "$ROOT/bench/$SCENARIO-rate.hf.yaml" ;;
  esac
}

# One measured step: a Hyperfoil warmup phase then the measured phase, under the same load.
#   closed: LOAD connections all out (bench/room.hf.yaml)
#   open:   LOAD requests/s over RATE_CONNS connections, each timed from when it was due (bench/room-rate.hf.yaml)
# The app's CPU is measured over the whole Hyperfoil run and divided by the responses of both phases;
# busy CPUs by the run's wall time. Returns 1 if the app is no longer running.
step() {
  local kind=$1 app=$2 rep=$3 load=$4 rate=${5:-$4} f run c0 c1 a0 a1 l0 l1 t0 t1 e0
  f=$OUT/$kind/$app-$rep-$(case $kind in closed) echo c ;; open) echo r ;; tabs) echo t ;; esac)$load run=$f-hyperfoil
  e0=$(log_errors "$app"); nstat -n > /dev/null
  c0=$(app_cpu_secs); a0=$(cpus_busy "$APP_CPUS"); l0=$(cpus_busy "$DRIVER_CPUS"); t0=$(now)
  drive "$kind" "$run" "$load" "$rate" > "$f.out" 2>&1 || true
  t1=$(now); a1=$(cpus_busy "$APP_CPUS"); l1=$(cpus_busy "$DRIVER_CPUS")
  if ! alive; then
    echo "failed=1" > "$f.properties"; log "$app exited during the step at $load ($OUT/$app.log)"; return 1
  fi
  c1=$(app_cpu_secs)
  cp "$(ls -d "$run"/run/* 2> /dev/null | tail -1)/stats/total.csv" "$f.csv" 2> /dev/null \
    || { echo "failed=1" > "$f.properties"; log "$app at $load: no Hyperfoil stats ($f.out)"; return 0; }
  { awk -v a="$c0" -v b="$c1" 'BEGIN { print "cpu_secs=" b - a }'
    awk -F, 'NR > 1 { n += $6 } END { print "responses=" n }' "$f.csv"
    awk -v a="$a0" -v b="$a1" -v s="$t0" -v e="$t1" 'BEGIN { print "app_cpus_busy=" (b - a) / (e - s) }'
    awk -v a="$l0" -v b="$l1" -v s="$t0" -v e="$t1" 'BEGIN { print "loadgen_busy=" (b - a) / (e - s) }'
    echo "app_cpus=$(cpu_list "$APP_CPUS" | wc -l)"
    echo "loadgen_cpus=$(cpu_list "$DRIVER_CPUS" | wc -l)"
    [ "$kind" = closed ] || awk -v r="$rate" 'BEGIN { s = int(r * 2) + 1; print "sessions=" (s < 100 ? 100 : s) }'
    echo "app_log_errors=$(( $(log_errors "$app") - e0 ))"
    app_mem; tcp_counters; } > "$f.properties"
  log "$app rep $rep: $kind $load"
}

# ---- run -------------------------------------------------------------------------------------
# The realistic workload at RATE page loads/s (open model), the writer committing the presence updates
# and messages that go with them (the visible tabs that load RATE pages/s: RATE x PAGE_EVERY) to the
# app's database during the step.
tab_writes() {  # RATE: the visible tabs that load RATE pages/s, their presence commits/s and messages/s
  awk -v r="$1" -v p="$PAGE_EVERY" -v q="$PRESENCE_EVERY" -v m="$MESSAGE_EVERY" \
    'BEGIN { t = int(r * p); printf "%d %.3f %.3f\n", t, t / q, t / m }'
}
tabs_step() {  # APP REP RATE
  local app=$1 rep=$2 rate=$3 tabs presence messages f rc=0
  read -r tabs presence messages <<< "$(tab_writes "$rate")"
  f=$OUT/tabs/$app-$rep-t$rate
  start_writer "$app" "$f.writer.txt" "$presence" "$messages"
  step tabs "$app" "$rep" "$rate" "$rate" || rc=$?
  stop_writer
  # the writer's own report: "writer: P presence commits, M message commits, F failed, ..."
  local wrote p m x
  wrote=$(sed -n 's/^writer: \([0-9]*\) presence commits, \([0-9]*\) message commits, \([0-9]*\) failed.*/\1 \2 \3/p' "$f.writer.txt" 2> /dev/null)
  if [ -f "$f.properties" ]; then
    printf 'offered=%s\ntabs=%s\npresence_per_sec=%s\nmessages_per_sec=%s\n' "$rate" "$tabs" "$presence" "$messages" >> "$f.properties"
    if [ -z "$wrote" ]; then
      echo "writer_failed=1" >> "$f.properties"; log "$app at $rate pages/s: the writer reported nothing ($f.writer.err)"
    else
      read -r p m x <<< "$wrote"; printf 'presence_commits=%s\nmessage_commits=%s\nwriter_failures=%s\n' "$p" "$m" "$x" >> "$f.properties"
    fi
  fi
  return $rc
}

cmd_run() {
  [ -x "$RUST/target/release/campfire" ] || die "run ./bench.sh setup first"
  local cores_list=$CORES cores
  [ -n "$APP_CPUS" ] && cores_list=custom
  for cores in $cores_list; do
    [ "$cores" = custom ] || place "$cores"
    OUT=$ROOT/results/runs/$(date +%Y%m%d-%H%M%S)-$(cpu_list "$APP_CPUS" | wc -l)c
    log "app CPUs $APP_CPUS, load driver CPUs $DRIVER_CPUS"
    preflight
    mkdir -p "$OUT/closed" "$OUT/open" "$OUT/tabs"; log "results: $OUT"
    prepare_workload
    describe > "$OUT/config.txt"
    reference
    local rep app load order
    for rep in $(seq 1 "$REPS"); do  # apps in alternating order
      order=$APPS; [ $((rep % 2)) = 0 ] && order=$(tr ' ' '\n' <<< "$APPS" | tac | tr '\n' ' ')
      for app in $order; do
        start_app "$app" "$APP_CPUS"; warm "$app"
        if [ "$WORKLOAD" = realistic ]; then
          for load in $RATES; do tabs_step "$app" "$rep" "$load" || break; done
        elif [ -n "$RATES" ]; then
          for load in $RATES; do step open "$app" "$rep" "$load" || break; done
        else
          for load in $CONNS; do step closed "$app" "$rep" "$load" || break; done
        fi
        stop_app
      done
    done
    report "$OUT" | tee "$OUT/report.txt"
    report "$OUT" --charts
  done
}

describe() {
  local cpu0; cpu0=$(cpu_list "$APP_CPUS" | head -1)
  echo "matrix=$MATRIX apps=$APPS reps=$REPS secs=$SECS warmup=$WARMUP hf_warmup=$HF_WARMUP $([ -n "$RATES" ] && echo "rates=$RATES rate_conns=$RATE_CONNS" || echo "conns=$CONNS")"
  echo "app_cpus=$APP_CPUS loadgen_cpus=$DRIVER_CPUS"
  [ "$WORKLOAD" = realistic ] && echo "workload=realistic rates=$RATES page_every=${PAGE_EVERY}s presence_every=${PRESENCE_EVERY}s message_every=${MESSAGE_EVERY}s sessions=$SESSIONS message_room=$MESSAGE_ROOM pages=$(wc -l < "$OUT/workload/pages.csv")"
  echo "cpu=$(lscpu | sed -n 's/^Model name: *//p') kernel=$(uname -r) governor=$(cat "/sys/devices/system/cpu/cpu$cpu0/cpufreq/scaling_governor" 2> /dev/null) boost=$(cat /sys/devices/system/cpu/cpufreq/boost 2> /dev/null) max_khz=$(cat "/sys/devices/system/cpu/cpu$cpu0/cpufreq/scaling_max_freq" 2> /dev/null)"
  echo "jdk_kotlin=$JDK_KOTLIN jdk_quarkus=$JDK_QUARKUS jvm_common=${JVM_COMMON[*]} jvm_quarkus=${JVM_QUARKUS[*]}"
  local libz; libz=$(ldconfig -p 2> /dev/null | awk '!found && /libz\.so\.1 \(libc6,x86-64\)/ { print $NF; found = 1 }')
  echo "quarkus_fragment_memory=${CAMPFIRE_FRAGMENT_MEMORY:-heap} quarkus_deflate=${CAMPFIRE_DEFLATE:-ffm} libz=$libz -> $(readlink -f "$libz" 2> /dev/null)"
  local d; for d in "$ROOT" "$RUST" "$KOTLIN" "$KOTLIN_POOLED"; do echo "$(basename "$d") $(git -C "$d" rev-parse --short HEAD) $(git -C "$d" status --short -- . ':!results/runs' | wc -l) changed"; done
}

# ---- profile ---------------------------------------------------------------------------------
LOAD_PID=""
cmd_profile() {
  case $PROFILE_APP in quarkus|kotlin) ;; *) die "profile needs --app quarkus or --app kotlin" ;; esac
  case $FORMAT in html|jfr|collapsed) ;; *) die "--format html, jfr or collapsed" ;; esac
  [[ $EVENT != *,* ]] || [ "$FORMAT" = jfr ] || die "several events (--event $EVENT) need --format jfr"
  [ -n "$APP_CPUS" ] || place "${CORES%% *}"
  # The load of a run's step: realistic (its first rate, with the writer), --rate (the first, open
  # model), else all out at the first --conns (default 1024).
  local kind=closed load=${override[CONNS]:-1024} tag
  if [ "$WORKLOAD" = realistic ]; then kind=tabs; load=$RATES
  elif [ -n "$RATES" ]; then kind=open; load=$RATES; fi
  load=${load%% *}; tag=$(case $kind in closed) echo c ;; open) echo r ;; tabs) echo t ;; esac)$load
  OUT=$ROOT/results/profiles; mkdir -p "$OUT"
  prepare_workload
  # Debug information at every instruction, not only at safepoints: without it the JIT's inlined code
  # is attributed to the nearest frame that has some, and flame graphs mislead.
  JVM_COMMON+=(-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints)
  start_app "$PROFILE_APP" "$APP_CPUS"
  all_out "$OUT/hyperfoil-warmup" 16 "$WARMUP" > /dev/null 2>&1 || true
  if [ "$kind" = tabs ]; then
    local tabs presence messages; read -r tabs presence messages <<< "$(tab_writes "$load")"
    start_writer "$PROFILE_APP" "$OUT/$PROFILE_APP-writer.txt" "$presence" "$messages"
  fi
  SECS=20 drive "$kind" "$OUT/hyperfoil-load" "$load" > "$OUT/hyperfoil-load.out" 2>&1 &
  LOAD_PID=$!
  trap 'kill "$LOAD_PID" 2> /dev/null || true; stop_writer; stop_app' EXIT
  local f java
  sleep $((HF_WARMUP + 3))  # past Hyperfoil's warmup phase: the profile sees the measured phase's load
  f=$OUT/$PROFILE_APP-${EVENT//,/+}-$(cpu_list "$APP_CPUS" | wc -l)c-$tag.$FORMAT
  java=$(jdk "$JDK_QUARKUS")
  # shellcheck disable=SC2086  # AP_OPTS: words on purpose
  # 10 s, one sample per ms of CPU (cpu, wall): N samples = N ms, samples / 10,000 = cores busy on average.
  local interval=(); case $EVENT in cpu|wall) interval=(-i 1ms) ;; esac
  (cd "$ROOT" && JAVA_HOME=$java jbang --quiet ap-loader profiler -e "$EVENT" -d 10 "${interval[@]}" ${AP_OPTS:-} -f "$f" "$APP_PID")
  wait "$LOAD_PID" || true
  stop_writer; stop_app
  [ "$kind" != tabs ] || log "$(head -1 "$OUT/$PROFILE_APP-writer.txt" 2> /dev/null)"
  log "profile: $f"
}

# ---- parity ----------------------------------------------------------------------------------
# Byte parity against the Rust port, as the Kotlin port's bench/parity.sh checks it: the room page for
# both user-agent branches and both encodings, then every request of its bench/requests.txt and of
# bench/requests-extra.txt, in order on one pair of apps. Status, decoded body and headers in order must
# match, less Date, X-Request-Id, X-Runtime, Last-Modified, signed cookie values and expiry times, and a
# gzipped body's Content-Length (zlib builds differ). requests-extra.txt (HTTP/1.0, Connection: close)
# is informational: the Kotlin port differs from Rust there too.
RUST_PID=""
cmd_parity() {
  local app=${1:-quarkus} extra_file=$ROOT/bench/requests-extra.txt
  case $app in quarkus|kotlin) ;; *) die "parity compares quarkus or kotlin with the Rust port" ;; esac
  OUT=$WORK/parity-$app; rm -rf "$OUT"; mkdir -p "$OUT"
  trap 'kill "$RUST_PID" 2> /dev/null || true; stop_app' EXIT
  local all; all=$(lscpu -p=CPU | grep -v '^#' | paste -sd,)
  PORT=4390 start_app rust "$all"; RUST_PID=$APP_PID; APP_PID=""
  PORT=4490 start_app "$app" "$all"
  checks=0 differences=0 informational=0
  local ua enc port
  for ua in '' 'curl/8.7.1'; do
    for enc in identity gzip; do
      for port in 4390 4490; do
        curl -s --compressed -H "User-Agent: $ua" -H 'Host: localhost:4390' -H "Cookie: $COOKIE" -H "Accept-Encoding: $enc" \
          -D "$OUT/$port.h" -o "$OUT/$port.body" "localhost:$port/rooms/$ROOM"
      done
      compare 0 "room page UA='$ua' $enc"
    done
  done
  local requests extra line fields first version method path args header
  for requests in "$KOTLIN/bench/requests.txt" "$extra_file"; do
    extra=0; [ "$requests" = "$extra_file" ] && extra=1
    while IFS= read -r line; do
      [ -z "$line" ] || [ "${line:0:1}" = "#" ] && continue
      IFS='|' read -r -a fields <<< "$line"
      read -r -a first <<< "${fields[0]}"
      version=()
      if [ "${first[0]}" = HTTP10 ]; then version=(--http1.0); first=("${first[@]:1}"); fi
      method=${first[0]} path=${first[1]}
      args=(-s --compressed "${version[@]}" -X "$method" -H 'Host: localhost:4390')
      [ "$method" = HEAD ] && args=(-s "${version[@]}" --head -H 'Host: localhost:4390')
      for header in "${fields[@]:1}"; do
        header=$(sed -e 's/^ *//' -e 's/ *$//' <<< "$header")
        args+=(-H "${header//\$COOKIE/$COOKIE}")
      done
      for port in 4390 4490; do
        if [ "$method" = HEAD ]; then curl "${args[@]}" -D "$OUT/$port.h" -o /dev/null "localhost:$port$path" || true; : > "$OUT/$port.body"
        else curl "${args[@]}" -D "$OUT/$port.h" -o "$OUT/$port.body" "localhost:$port$path" || true; fi
      done
      compare "$extra" "$line"
    done < "$requests"
  done
  echo "$app: $checks checks, $differences different, $informational informational differences (requests-extra.txt)"
  [ "$differences" = 0 ]
}
normalized() {
  tr -d '\r' < "$1" | grep -v -i -E '^(date|x-request-id|x-runtime|last-modified):' \
    | sed -E 's/^(set-cookie: (_campfire_session|session_token)=)[^;]*/\1<signed>/; s/(expires=)[^;]*/\1<time>/' \
    | if grep -q -i '^content-encoding: *gzip' "$1"; then sed -E 's/^(content-length: ).*/\1<gzip length>/I'; else cat; fi
}
compare() {  # EXTRA DESCRIPTION
  checks=$((checks + 1))
  if cmp -s "$OUT/4390.body" "$OUT/4490.body" && diff -q <(normalized "$OUT/4390.h") <(normalized "$OUT/4490.h") > /dev/null; then
    echo "identical  $(head -1 "$OUT/4490.h" | tr -d '\r' | cut -d' ' -f2) $(wc -c < "$OUT/4490.body")B  ${2:0:100}"
  else
    if [ "$1" = 1 ]; then informational=$((informational + 1)); echo "differs (informational)  ${2:0:100}"
    else differences=$((differences + 1)); echo "DIFFERENT  ${2:0:100}"; fi
    diff <(normalized "$OUT/4390.h") <(normalized "$OUT/4490.h") | head -12 || true
    cmp "$OUT/4390.body" "$OUT/4490.body" | head -2 || true
  fi
}

# ---- options ---------------------------------------------------------------------------------
command=${1:-}; [ $# -gt 0 ] && shift
case $command in
  ''|-h|--help|help) usage; exit 0 ;;
  report) report "$@"; exit ;;
esac
MATRIX=article APP_CPUS="" DRIVER_CPUS="" RATES="" FORCE=0 EVENT=cpu FORMAT=html PROFILE_APP="" ARGS=()
declare -A override=()
while [ $# -gt 0 ]; do
  case $1 in
    --matrix) MATRIX=$2; shift ;;
    --cores) override[CORES]=$2; shift ;;
    --app-cpus) APP_CPUS=$2; shift ;;
    --driver-cpus) DRIVER_CPUS=$2; shift ;;
    --apps) override[APPS]=${2//,/ }; shift ;;
    --conns) override[CONNS]=${2//,/ }; shift ;;
    --rate-conns) [[ ${2:-} =~ ^[1-9][0-9]*$ ]] || die "--rate-conns needs a connection count"; override[RATE_CONNS]=$2; shift ;;
    --reps) override[REPS]=$2; shift ;;
    --secs) override[SECS]=$2; shift ;;
    --rate) [[ ${2:-} =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] || die "--rate needs the rates, e.g. --rate 20000,40000"
      RATES=${2//,/ }; shift ;;
    --sessions) override[SESSIONS]=$2; shift ;;
    --page-every) override[PAGE_EVERY]=$2; shift ;;
    --presence-every) override[PRESENCE_EVERY]=$2; shift ;;
    --message-every) override[MESSAGE_EVERY]=$2; shift ;;
    --message-room) override[MESSAGE_ROOM]=$2; shift ;;
    --force) FORCE=1 ;;
    --event) EVENT=$2; shift ;;
    --format) FORMAT=$2; shift ;;
    --app) PROFILE_APP=$2; shift ;;
    -*) die "unknown option $1 (./bench.sh --help)" ;;
    *) ARGS+=("$1") ;;
  esac
  shift
done
[ -f "$ROOT/bench/matrix/$MATRIX.conf" ] || die "no matrix bench/matrix/$MATRIX.conf"
WORKLOAD=room CONNS="" SESSIONS=0 PAGE_EVERY=60 PRESENCE_EVERY=50 MESSAGE_EVERY=600 MESSAGE_ROOM=all  # a matrix may set them (realistic)
RATES_GIVEN=$RATES
source "$ROOT/bench/matrix/$MATRIX.conf"
[ -z "$RATES_GIVEN" ] || RATES=$RATES_GIVEN  # --rate wins over a matrix's rates
[ "$WORKLOAD" != realistic ] || [ -n "$RATES" ] || die "--matrix realistic needs page loads/s: --rate R,R... (or RATES in the matrix)"
for k in "${!override[@]}"; do printf -v "$k" '%s' "${override[$k]}"; done
SCENARIO=$WORKLOAD  # bench/room*.hf.yaml or bench/realistic*.hf.yaml
{ [ -n "$APP_CPUS" ] && [ -n "$DRIVER_CPUS" ]; } || { [ -z "$APP_CPUS" ] && [ -z "$DRIVER_CPUS" ]; } || die "--app-cpus and --driver-cpus go together"
[ -z "$APP_CPUS" ] || [ -z "${override[CORES]:-}" ] || die "--cores picks the CPUs; with --app-cpus and --driver-cpus, leave it out"

ulimit -n 1048576 2> /dev/null || ulimit -n "$(ulimit -Hn)"  # 16k+ connections on each side
case $command in
  setup) lock; cmd_setup ;;
  parity) lock; cmd_parity "${ARGS[@]}" ;;
  run) lock; cmd_run ;;
  profile) lock; cmd_profile ;;
  *) usage; exit 1 ;;
esac
