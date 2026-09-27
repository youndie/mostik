#!/usr/bin/env bash
# B-10: start mostik on a port another process holds, and see what an operator gets.
#
#   ci/b-10/run.sh native|jvm
#
# Expected: exit 1, one sentence naming the port and MOSTIK_PORT, no core dump (exit 134 is SIGABRT).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=18105
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm" >&2; exit 2 ;;
esac
# The holder: a listening socket that accepts nothing and ends by itself. Not `nc -l`, which waits for a
# connection that never comes and holds the session (B-07's harness finding).
python3 -c "import socket,time; s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(('0.0.0.0', $PORT)); s.listen(1); time.sleep(20)" &
holder=$!
sleep 0.5
out=$(env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders timeout 15 "${LAUNCH[@]}" 2>&1)
code=$?
kill "$holder" 2> /dev/null; wait "$holder" 2> /dev/null
rest=$(printf '%s\n' "$out" | grep -v -E '^SLF4J|^%[0-9]|^configured:|^\[INFO\]')
lines=$(printf '%s\n' "$rest" | grep -c .)
echo "[$1] exit $code | $lines line(s) besides the usual start-up ones | first: $(printf '%s\n' "$rest" | head -1 | cut -c1-160)"
if [ "$code" = 1 ] && printf '%s' "$out" | grep -q "MOSTIK_PORT" && [ "$lines" -le 2 ]; then echo "[$1] PASS"; exit 0; fi
echo "[$1] FAIL"; exit 1
