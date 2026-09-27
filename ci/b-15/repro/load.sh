#!/usr/bin/env bash
# Drive the minimal server with B-15's load: 64 clients, curl per request, for [seconds]; count answers that never
# came (curl 52 "Empty reply from server", 56 "Recv failure").
#
#   ci/b-15/repro/load.sh [seconds] [native|jvm]
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SECONDS_TO_RUN=${1:-1200}
BUILD=${2:-native}
case "$BUILD" in
  native) LAUNCH=("$HERE/build/bin/linuxX64/releaseExecutable/b15-repro.kexe") ;;
  # The JVM control runs the compiled classes on their runtime classpath, which `jvmRuntimeClasspath.txt` holds.
  jvm) LAUNCH=(java -cp "$HERE/build/classes/kotlin/jvm/main:$(cat "$HERE/build/jvmRuntimeClasspath.txt")" MainKt) ;;
  *) echo "usage: load.sh [seconds] [native|jvm]" >&2; exit 2 ;;
esac
WORK=$(mktemp -d /tmp/b15-repro-XXXX)
"${LAUNCH[@]}" > "$WORK/server.log" 2>&1 &
pid=$!
for _ in $(seq 1 150); do curl -s -o /dev/null -X POST http://127.0.0.1:18108/records --data-binary w && break; sleep 0.2; done
until=$(( $(date +%s%3N) + SECONDS_TO_RUN * 1000 ))
client() {
    local n=0 out rc
    while [ "$(date +%s%3N)" -lt "$until" ]; do
        n=$((n + 1))
        out=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST http://127.0.0.1:18108/records --data-binary "v$n")
        rc=$?
        echo "$1-$n $out $rc" >> "$2"
        sleep 0.02
    done
}
workers=()
for c in $(seq 1 64); do client "c$c" "$WORK/ledger.$c" & workers+=($!); done
for w in "${workers[@]}"; do wait "$w"; done
kill "$pid"
cat "$WORK"/ledger.* | awk -v secs="$SECONDS_TO_RUN" -v build="$BUILD" '{ total++; st[$2]++; if ($3 == 52 || $3 == 56) { n++; rc[$3]++ } }
    END { s = ""; for (k in st) s = s k "x" st[k] " "; printf "[repro " build "] %d requests in %d s | %s| no answer: %d (curl 52: %d, 56: %d)\n", total, secs, s, n + 0, rc[52] + 0, rc[56] + 0 }'
grep -v "Application started\|Responding at" "$WORK/server.log" | head -5
echo "[repro] work: $WORK"
