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
#   --reps N  --secs N     all-out repetitions and seconds per step (the fixed-rate phase uses the matrix's OPEN_REPS)
#   --rate               then the fixed-rate phase: rates derived from this run's all-out results
#   --force                measure even if the preflight finds a problem
#   --app A                (profile) the app: quarkus or kotlin
#   --event E[,E...]       (profile) async-profiler events: cpu (default), alloc, wall, lock; several need --format jfr
#   --format F             (profile) html (flame graph, default), jfr (for JFR tools and the Jafar MCP server), collapsed
# Environment: JDK_KOTLIN, JDK_QUARKUS (SDKMAN identifiers), BENCH_LOCK (the lock file runs share),
#   AP_OPTS (more async-profiler options for profile, e.g. "-i 100us").
set -euo pipefail
export LC_ALL=C
ROOT=$(cd "$(dirname "$0")" && pwd)

# ---- what is measured ------------------------------------------------------------------------
JDK_KOTLIN=${JDK_KOTLIN:-25.0.3-tem}     # the Kotlin port as published
JDK_QUARKUS=${JDK_QUARKUS:-27.0.0-tem}   # Quarkus: build and run
PORT=4390 ROOM=486777696
RUST=$ROOT/upstream/once-campfire-rust KOTLIN=$ROOT/upstream/once-campfire-kotlin
WORK=$ROOT/target                        # app run directories, build logs (not committed)
COOKIE=$(cat "$ROOT/fixtures/cookie.txt")

# Both JVMs: ParallelGC (the Kotlin port's published JAVA_OPTS), compact headers, a 512 MB heap ceiling.
JVM_COMMON=(-XX:+UseParallelGC -XX:+UseCompactObjectHeaders -Xmx512m --enable-native-access=ALL-UNNAMED)
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
  git submodule update --init upstream/once-campfire-rust upstream/once-campfire-kotlin
  git -C "$KOTLIN" submodule update --init --depth 1 reference

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

  log "building the Quarkus app (it uses the Kotlin port's Rails-format modules as jars)"
  local version m lib=$KOTLIN/campfire/build/install/campfire/lib
  version=$(git -C "$KOTLIN" rev-parse --short HEAD)
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
  cp "$ROOT/fixtures/seed.sqlite3" "$dir/db/production.sqlite3"
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
stop_app() {
  [ -n "$APP_PID" ] || return 0
  kill "$APP_PID" 2> /dev/null || true; wait "$APP_PID" 2> /dev/null || true; APP_PID=""
}
alive() { kill -0 "$APP_PID" 2> /dev/null; }
trap stop_app EXIT

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
  local max=0; for c in $CONNS $OPEN_CONNS; do [ "$c" -gt "$max" ] && max=$c; done
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
  (cd "$ROOT" && JAVA_HOME=$java taskset -c "$DRIVER_CPUS" jbang --quiet --java-options=-Xmx4g \
    --java-options=-Dio.hyperfoil.rootdir="$run" --java-options=-Dio.hyperfoil.cpu.watchdog.idle.threshold=0.0 hyperfoil \
    -PPORT="$PORT" -PROOM="$ROOM" -PCOOKIE="$COOKIE" -PTHREADS="$(cpu_list "$DRIVER_CPUS" | wc -l)" "$@")
}
all_out() {  # RUN_DIR CONNS SECS: the closed-loop scenario, no warmup phase of its own
  hyperfoil "$1" -PCONNS="$2" -PUSERS="$2" -PWARMUP=1s -PDURATION="$3s" "$ROOT/bench/room.hf.yaml"
}

# The request every load step sends (bench/*.hf.yaml), with curl: the decoded body to stdout.
fetch() {
  curl -fsS --compressed -A curl/8.7.1 -H 'accept: */*' -H "cookie: $COOKIE" -H 'accept-encoding: gzip' "http://127.0.0.1:$PORT/rooms/$ROOM"
}

# The Rust port's page, fetched once per run: what every app must serve, byte for byte.
reference() {
  start_app rust "$APP_CPUS"
  fetch > "$OUT/expected.html" || die "Rust didn't serve the room page"
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
  all_out "$OUT/warmup-$app" 16 "$WARMUP" > "$OUT/warmup-$app.out" 2>&1 || true
}

# One measured step: a Hyperfoil warmup phase then the measured phase, under the same load.
#   closed: LOAD connections all out (bench/room.hf.yaml)
#   open:   LOAD requests/s over OPEN_CONNS connections, each timed from when it was due (bench/room-rate.hf.yaml)
# The app's CPU is measured over the whole Hyperfoil run and divided by the responses of both phases;
# busy CPUs by the run's wall time. Returns 1 if the app is no longer running.
step() {
  local kind=$1 app=$2 rep=$3 load=$4 f run c0 c1 a0 a1 l0 l1 t0 t1 e0
  f=$OUT/$kind/$app-$rep-$([ "$kind" = closed ] && echo c || echo r)$load run=$f-hyperfoil
  e0=$(log_errors "$app"); nstat -n > /dev/null
  c0=$(app_cpu_secs); a0=$(cpus_busy "$APP_CPUS"); l0=$(cpus_busy "$DRIVER_CPUS"); t0=$(now)
  case $kind in
    closed) hyperfoil "$run" -PCONNS="$load" -PUSERS="$load" -PDURATION="${SECS}s" -PWARMUP="${HF_WARMUP}s" "$ROOT/bench/room.hf.yaml" ;;
    open) hyperfoil "$run" -PCONNS="$OPEN_CONNS" -PRATE="$load" -PSESSIONS=$((load * 2)) -PDURATION="${SECS}s" -PWARMUP="${HF_WARMUP}s" "$ROOT/bench/room-rate.hf.yaml" ;;
  esac > "$f.out" 2>&1 || true
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
    [ "$kind" = open ] && echo "sessions=$((load * 2))"
    echo "app_log_errors=$(( $(log_errors "$app") - e0 ))"
    app_mem; tcp_counters; } > "$f.properties"
  log "$app rep $rep: $kind $load"
}

# ---- run -------------------------------------------------------------------------------------
cmd_run() {
  [ -x "$RUST/target/release/campfire" ] || die "run ./bench.sh setup first"
  local cores_list=$CORES cores
  [ -n "$APP_CPUS" ] && cores_list=custom
  for cores in $cores_list; do
    [ "$cores" = custom ] || place "$cores"
    OUT=$ROOT/results/runs/$(date +%Y%m%d-%H%M%S)-$(cpu_list "$APP_CPUS" | wc -l)c
    log "app CPUs $APP_CPUS, load driver CPUs $DRIVER_CPUS"
    preflight
    mkdir -p "$OUT/closed" "$OUT/open"; log "results: $OUT"
    describe > "$OUT/config.txt"
    reference
    local rep app load order
    for rep in $(seq 1 "$REPS"); do  # apps in alternating order
      order=$APPS; [ $((rep % 2)) = 0 ] && order=$(tr ' ' '\n' <<< "$APPS" | tac | tr '\n' ' ')
      for app in $order; do
        start_app "$app" "$APP_CPUS"; warm "$app"
        for load in $CONNS; do step closed "$app" "$rep" "$load" || break; done
        stop_app
      done
    done
    if [ "$OPEN" = 1 ]; then
      # Offered rates: fractions of the best app's median all-out req/s at 64 connections.
      local top f
      top=$(report "$OUT" --capacity)
      if [ "$top" -gt 0 ]; then
        for rep in $(seq 1 "$OPEN_REPS"); do
          order=$APPS; [ $((rep % 2)) = 0 ] && order=$(tr ' ' '\n' <<< "$APPS" | tac | tr '\n' ' ')
          for app in $order; do
            start_app "$app" "$APP_CPUS"; warm "$app"
            for f in $OPEN_FRACTIONS; do
              load=$(awk -v t="$top" -v f="$f" 'BEGIN { print int(t * f / 500) * 500 }')
              [ "$load" -gt 0 ] || continue
              step open "$app" "$rep" "$load" || break
            done
            stop_app
          done
        done
      else log "no all-out result at 64 connections: skipping the fixed-rate phase"; fi
    fi
    report "$OUT" | tee "$OUT/report.txt"
    report "$OUT" --charts
  done
}

describe() {
  local cpu0; cpu0=$(cpu_list "$APP_CPUS" | head -1)
  echo "matrix=$MATRIX apps=$APPS conns=$CONNS reps=$REPS secs=$SECS warmup=$WARMUP hf_warmup=$HF_WARMUP open=$OPEN open_reps=$OPEN_REPS open_conns=$OPEN_CONNS open_fractions=$OPEN_FRACTIONS"
  echo "app_cpus=$APP_CPUS loadgen_cpus=$DRIVER_CPUS"
  echo "cpu=$(lscpu | sed -n 's/^Model name: *//p') kernel=$(uname -r) governor=$(cat "/sys/devices/system/cpu/cpu$cpu0/cpufreq/scaling_governor" 2> /dev/null) boost=$(cat /sys/devices/system/cpu/cpufreq/boost 2> /dev/null) max_khz=$(cat "/sys/devices/system/cpu/cpu$cpu0/cpufreq/scaling_max_freq" 2> /dev/null)"
  echo "jdk_kotlin=$JDK_KOTLIN jdk_quarkus=$JDK_QUARKUS jvm_common=${JVM_COMMON[*]} jvm_quarkus=${JVM_QUARKUS[*]}"
  local d; for d in "$ROOT" "$RUST" "$KOTLIN"; do echo "$(basename "$d") $(git -C "$d" rev-parse --short HEAD) $(git -C "$d" status --short | wc -l) changed"; done
}

# ---- profile ---------------------------------------------------------------------------------
LOAD_PID=""
cmd_profile() {
  case $PROFILE_APP in quarkus|kotlin) ;; *) die "profile needs --app quarkus or --app kotlin" ;; esac
  case $FORMAT in html|jfr|collapsed) ;; *) die "--format html, jfr or collapsed" ;; esac
  [[ $EVENT != *,* ]] || [ "$FORMAT" = jfr ] || die "several events (--event $EVENT) need --format jfr"
  [ -n "$APP_CPUS" ] || place "${CORES%% *}"
  local conns=${override[CONNS]:-1024}; conns=${conns%% *}
  OUT=$ROOT/results/profiles; mkdir -p "$OUT"
  start_app "$PROFILE_APP" "$APP_CPUS"
  all_out "$OUT/hyperfoil-warmup" "$conns" "$WARMUP" > /dev/null 2>&1 || true
  all_out "$OUT/hyperfoil-load" "$conns" $((SECS + 10)) > /dev/null 2>&1 &
  LOAD_PID=$!
  trap 'kill "$LOAD_PID" 2> /dev/null || true; stop_app' EXIT
  local f java
  sleep 5  # Hyperfoil started and loading
  f=$OUT/$PROFILE_APP-${EVENT//,/+}-$(cpu_list "$APP_CPUS" | wc -l)c-c$conns.$FORMAT
  java=$(jdk "$JDK_QUARKUS")
  # shellcheck disable=SC2086  # AP_OPTS: words on purpose
  (cd "$ROOT" && JAVA_HOME=$java jbang --quiet ap-loader profiler -e "$EVENT" -d "$SECS" ${AP_OPTS:-} -f "$f" "$APP_PID")
  wait "$LOAD_PID" || true
  stop_app
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
MATRIX=article APP_CPUS="" DRIVER_CPUS="" FORCE=0 EVENT=cpu FORMAT=html PROFILE_APP="" ARGS=()
declare -A override=()
while [ $# -gt 0 ]; do
  case $1 in
    --matrix) MATRIX=$2; shift ;;
    --cores) override[CORES]=$2; shift ;;
    --app-cpus) APP_CPUS=$2; shift ;;
    --driver-cpus) DRIVER_CPUS=$2; shift ;;
    --apps) override[APPS]=${2//,/ }; shift ;;
    --conns) override[CONNS]=${2//,/ }; shift ;;
    --reps) override[REPS]=$2; shift ;;
    --secs) override[SECS]=$2; shift ;;
    --rate) override[OPEN]=1 ;;
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
source "$ROOT/bench/matrix/$MATRIX.conf"
for k in "${!override[@]}"; do printf -v "$k" '%s' "${override[$k]}"; done
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
