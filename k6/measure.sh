#!/usr/bin/env bash
# Three numbers about a running binary: time to ready, RSS at ready, p95 at a fixed rate.
#
# THE REFUSAL IS THE POINT OF THIS SCRIPT, not the measurement. A local run prints and exits; only a
# run that names two hosts may write into docs/research/. The last blog post in this portfolio shipped
# a wrong table because a laptop number was written down as if it were a measurement, and a refusal is
# the only mechanism that survives somebody being in a hurry.
set -uo pipefail

STAND=""
RATE="${RATE:-200}"
DURATION="${DURATION:-30s}"
BINARY="${BINARY:-server/build/native-image/keel}"
for arg in "$@"; do
  case "$arg" in
    --stand=*) STAND="${arg#--stand=}" ;;
    --rate=*) RATE="${arg#--rate=}" ;;
    --duration=*) DURATION="${arg#--duration=}" ;;
    --binary=*) BINARY="${arg#--binary=}" ;;
  esac
done

if [ -n "$STAND" ]; then
  SUBJECT="${STAND%%,*}"; GENERATOR="${STAND##*,}"
  if [ "$SUBJECT" = "$GENERATOR" ] || [ -z "$SUBJECT" ] || [ -z "$GENERATOR" ]; then
    echo "measure: --stand needs two DIFFERENT hosts as subject,generator — got '$STAND'." >&2
    echo "         One host means the generator competes with the subject for CPU, which is the" >&2
    echo "         measurement error this flag exists to prevent." >&2
    exit 2
  fi
  echo "measure: a stand run against $SUBJECT driven from $GENERATOR is not implemented." >&2
  echo "         It is deliberately absent rather than written and never run: orchestration nobody" >&2
  echo "         has executed would look like a measurement capability this repository does not have." >&2
  echo "         B-13 in backlog.md carries what it has to do." >&2
  exit 3
fi

# ---- the local run: measure, print, refuse to write -------------------------------------------
PORT="${PORT:-18200}"
DB="$(mktemp -d)/measure.db"
started=$(date +%s.%N)
KEEL_DB_PATH="$DB" KEEL_PORT="$PORT" "$BINARY" >/tmp/measure-subject.log 2>&1 &
pid=$!
for _ in $(seq 1 400); do curl -sf "localhost:$PORT/health/ready" >/dev/null 2>&1 && break; sleep 0.05; done
ready=$(date +%s.%N)
rss=$(awk '/VmRSS/ {print $2}' "/proc/$pid/status" 2>/dev/null || echo "?")

KEEL_BASE="http://127.0.0.1:$PORT" KEEL_TARGET=measure KEEL_RATE="$RATE" KEEL_DURATION="$DURATION" \
  k6 run --quiet --summary-trend-stats="avg,p(95),p(99)" k6/items.js 2>/dev/null | tail -2

kill -TERM "$pid" 2>/dev/null; wait "$pid" 2>/dev/null

echo
echo "  time to ready : $(echo "$ready - $started" | bc) s"
echo "  RSS at ready  : ${rss} kB"
echo "  rate          : ${RATE}/s for ${DURATION}"
echo
echo "NOT A MEASUREMENT. One host: the generator and the subject shared this machine's CPU, so the"
echo "latency above describes this laptop and not the service. Nothing was written to docs/research/;"
echo "only a --stand run naming two hosts may write there."
