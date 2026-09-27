#!/usr/bin/env bash
# B-13: does the build restart on its own port right after a shutdown that left connections in TIME_WAIT?
#
#   ci/b-13/run.sh native|jvm
#
# 20 publishes with `Connection: close`, so the server closes each connection and holds it in TIME_WAIT; then
# SIGTERM, and a restart on the same port as soon as the old process has exited. keel found that native CIO applies
# reuseAddress=false literally and refused such a restart while the JVM served it (youndie/keel@e6a12eb).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=18106
URL="http://127.0.0.1:$PORT"
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm" >&2; exit 2 ;;
esac
# Started in THIS shell, never in $(...): a pid from a subshell cannot be waited for, and a restart that does not wait
# for the first process to exit measures a busy port, not TIME_WAIT (the first version of this script did that).
run_env=(MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders)
gone() { for _ in $(seq 1 400); do kill -0 "$1" 2> /dev/null || return 0; sleep 0.1; done; return 1; }
"$ROOT/ci/broker/broker.sh" up > /dev/null
env "${run_env[@]}" "${LAUNCH[@]}" > "/tmp/mostik-b13-$1-first.log" 2>&1 &
pid=$!
for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done
ok=0
for i in $(seq 1 20); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Connection: close' -X POST "$URL/topics/orders/records" --data-binary "t$i")" = 200 ] && ok=$((ok + 1))
done
waiting=$(ss -tan state time-wait "( sport = :$PORT )" | tail -n +2 | wc -l)
kill -TERM "$pid"
gone "$pid" || { echo "[$1] the first process did not exit" >&2; exit 1; }
if ss -ltn "( sport = :$PORT )" | tail -n +2 | grep -q .; then echo "[$1] something still LISTENS on :$PORT" >&2; exit 1; fi
env "${run_env[@]}" "${LAUNCH[@]}" > "/tmp/mostik-b13-$1-second.log" 2>&1 &
second=$!
ready=000
for _ in $(seq 1 50); do ready=$(curl -s -o /dev/null -w '%{http_code}' "$URL/health/ready"); [ "$ready" = 200 ] && break; kill -0 "$second" 2> /dev/null || break; sleep 0.2; done
alive=no; kill -0 "$second" 2> /dev/null && alive=yes
kill -TERM "$second" 2> /dev/null; gone "$second"
echo "[$1] $ok/20 published with Connection: close | TIME_WAIT on :$PORT at SIGTERM: $waiting | restart: ready=$ready alive=$alive | $(grep -m1 -i 'cannot be listened\|Exception' "/tmp/mostik-b13-$1-second.log" | cut -c1-140)"
[ "$ready" = 200 ] && { echo "[$1] PASS"; exit 0; }
echo "[$1] FAIL"; exit 1
