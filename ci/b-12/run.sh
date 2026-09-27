#!/usr/bin/env bash
# What a client sees during kore's shutdown, probed every ~220 ms after SIGTERM (B-12, then B-14).
#
#   ci/b-12/run.sh native|jvm
#
# Expected since kore 0.1.7 (B-14): through the 5 s announce, /health/ready answers 503 (the proxy stops sending) and
# a publish is still served, 200 (what the proxy already sent is answered). From the drain on, a publish is 503 or
# the connection is refused. No refused connection before the drain.
#
# Under kore 0.1.6 and older the refusal opened at the announce, so a publish was 503 from the first millisecond:
# this script fails there, which is its positive control (B-14's findings).
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
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/topics/orders/records" --data-binary warm)" = 200 ] \
    || { echo "[$1] the warm-up publish failed" >&2; exit 1; }
kill -TERM "$pid"
start=$(date +%s%N); served=0; wrong=0; first_refused=""; first_publish_503=""; line=""
for _ in $(seq 1 40); do
    t=$(( ($(date +%s%N) - start) / 1000000 ))
    r=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:$PORT/health/ready")
    p=$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 -X POST "http://127.0.0.1:$PORT/topics/orders/records" --data-binary x)
    line="$line ${t}:r$r/p$p"
    # A publish is judged through the whole announce. Readiness only from 100 ms on: the signal is handled off the
    # signal handler, and the first probe, 2 ms after SIGTERM on native, can still see 200 (B-14's first run).
    if [ "$t" -lt 4500 ]; then
        if [ "$p" = 200 ] && { [ "$r" = 503 ] || [ "$t" -lt 100 ]; }; then served=$((served + 1)); else wrong=$((wrong + 1)); fi
    fi
    [ "$p" = 503 ] && [ -z "$first_publish_503" ] && first_publish_503=$t
    [ "$r" = 000 ] && [ -z "$first_refused" ] && first_refused=$t
    kill -0 "$pid" 2> /dev/null || break
    sleep 0.2
done
wait "$pid"; code=$?
echo "[$1] exit $code | announce probes with readiness 503 and publish 200: $served, other: $wrong | first publish 503 at ${first_publish_503:-never} ms | first refused at ${first_refused:-never} ms | $(grep -E '^ANNOUNCE' "/tmp/mostik-b12-$1.log")"
if [ "$served" -gt 0 ] && [ "$wrong" = 0 ] && { [ -z "$first_refused" ] || [ "$first_refused" -ge 4500 ]; }; then
    echo "[$1] PASS"; exit 0
fi
echo "[$1] FAIL |$line" | cut -c1-400; exit 1
