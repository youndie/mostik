#!/usr/bin/env bash
# B-12: what a client sees during kore's announce, probed every ~220 ms after SIGTERM.
#
#   ci/b-12/run.sh native|jvm
#
# Expected on both builds: /health/ready and a publish answer 503 (kore's refusal) until the announce ends, and
# only then is the connection refused. Before the fix, the JVM build refused from 1 ms on (000 below).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=18104
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm" >&2; exit 2 ;;
esac
"$ROOT/ci/broker/broker.sh" up > /dev/null
env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders "${LAUNCH[@]}" > "/tmp/mostik-b12-$1.log" 2>&1 &
pid=$!
for _ in $(seq 1 100); do curl -s -o /dev/null "http://127.0.0.1:$PORT/health/ready" && break; sleep 0.2; done
kill -TERM "$pid"
start=$(date +%s%N); refused_before_ms=""; served503=0
for _ in $(seq 1 40); do
    t=$(( ($(date +%s%N) - start) / 1000000 ))
    r=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:$PORT/health/ready")
    p=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 -X POST "http://127.0.0.1:$PORT/topics/orders/records" --data-binary x)
    if [ "$r" = 503 ] && [ "$p" = 503 ]; then served503=$((served503 + 1)); fi
    if [ "$r" = 000 ] && [ -z "$refused_before_ms" ]; then refused_before_ms=$t; fi
    kill -0 "$pid" 2> /dev/null || break
    sleep 0.2
done
wait "$pid"; code=$?
echo "[$1] exit $code | probes answered 503 on both: $served503 | first refused connection at ${refused_before_ms:-never} ms | $(grep -E '^ANNOUNCE' "/tmp/mostik-b12-$1.log")"
# The announce is 5 s: a build that refuses before 4 500 ms has stopped listening during it.
[ -n "$refused_before_ms" ] && [ "$refused_before_ms" -lt 4500 ] && { echo "[$1] FAIL: the listener closed during the announce" >&2; exit 1; }
echo "[$1] PASS"
