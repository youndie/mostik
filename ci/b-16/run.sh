#!/usr/bin/env bash
# B-16: a SIGTERM the moment the service first answers readiness, repeated. kore 0.1.9 took the signal from Ktor's
# native handler in exactly this window (kore B-63), and 0.1.10 moved the handler to C (kore B-64).
#
#   ci/b-16/run.sh native|jvm [starts]
#
# Every start must end by itself with the clean exit of its build (native 0, JVM 143) within LIMIT seconds. A hang is
# killed and counted, and so is a death by signal (native 139 is B-64's).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=18116
STARTS=${2:-100}
LIMIT=30
STAND_IN='
import http.server, os, signal, sys
if sys.argv[1] == "hang":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
else:
    signal.signal(signal.SIGTERM, lambda *_: os.kill(os.getpid(), signal.SIGSEGV))
class Ready(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers()
    def log_message(self, *_):
        pass
http.server.HTTPServer(("127.0.0.1", 18116), Ready).serve_forever(poll_interval=0.05)
'
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe"); CLEAN=0 ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution"); CLEAN=143 ;;
  # The script's own controls: a stand-in that answers readiness and then ignores SIGTERM must count as hung, and
  # one that dies of SIGSEGV on it must count as 139. A clean run means something only if both are caught.
  control-hang|control-crash) LAUNCH=(python3 -c "$STAND_IN" "${1#control-}"); CLEAN=0 ;;
  *) echo "usage: run.sh native|jvm|control-hang|control-crash [starts]" >&2; exit 2 ;;
esac
"$ROOT/ci/broker/broker.sh" up > /dev/null || exit 1
WORK=$(mktemp -d /tmp/mostik-b16-XXXX)
clean=0; hung=0; other=0; never=0; codes=""; slowest=0
for i in $(seq 1 "$STARTS"); do
    env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders "${LAUNCH[@]}" \
        > "$WORK/run.$i.log" 2>&1 &
    pid=$!
    ready=""
    for _ in $(seq 1 1000); do
        [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://127.0.0.1:$PORT/health/ready")" = 200 ] \
            && { ready=1; break; }
        kill -0 "$pid" 2> /dev/null || break
        sleep 0.01
    done
    kill -TERM "$pid" 2> /dev/null
    t0=$(date +%s%3N)
    for _ in $(seq 1 $((LIMIT * 20))); do kill -0 "$pid" 2> /dev/null || break; sleep 0.05; done
    if kill -0 "$pid" 2> /dev/null; then
        kill -KILL "$pid"; wait "$pid" 2> /dev/null; hung=$((hung + 1)); codes="$codes $i:hung"; continue
    fi
    wait "$pid"; code=$?
    took=$(( $(date +%s%3N) - t0 )); [ "$took" -gt "$slowest" ] && slowest=$took
    [ -z "$ready" ] && never=$((never + 1))
    if [ "$code" = "$CLEAN" ]; then clean=$((clean + 1)); else other=$((other + 1)); codes="$codes $i:$code"; fi
done
echo "[$1] $STARTS starts | clean exit $CLEAN: $clean | hung: $hung | other: $other${codes:+ ($codes )} | never ready: $never | slowest stop ${slowest} ms"
echo "[$1] work: $WORK"
[ "$clean" = "$STARTS" ] && [ "$never" = 0 ]
