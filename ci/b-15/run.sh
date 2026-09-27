#!/usr/bin/env bash
# B-15: does a publish ever get no answer while its record is written, in normal serving?
#
#   ci/b-15/run.sh native|jvm [seconds]
#
# 64 clients publish unique keys for [seconds] (600 by default), each keeping a ledger line per request: key, HTTP
# status, curl's exit code, start and end (epoch ms). The clients stop BEFORE the service does, so nothing here is a
# shutdown artefact (B-11). Then the run's topic is read once with the broker distribution's consumer, and every
# request without an answer (curl 52 or 56) is looked up in it.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PORT=18107
URL="http://127.0.0.1:$PORT"
SECONDS_TO_RUN=${2:-600}
CLIENTS=64
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm [seconds]" >&2; exit 2 ;;
esac
K="docker exec mostik-broker /opt/kafka/bin"
WORK=$(mktemp -d /tmp/mostik-b15-XXXX)
"$ROOT/ci/broker/broker.sh" up > /dev/null || exit 1
topic="b15-$1-$(date +%s%N)"
$K/kafka-topics.sh --bootstrap-server 127.0.0.1:19092 --create --topic "$topic" --partitions 3 --replication-factor 1 > /dev/null || exit 1
env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS="$topic" "${LAUNCH[@]}" > "$WORK/mostik.log" 2>&1 &
pid=$!
for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done
started=$(date +%s%3N)
until=$(( started + SECONDS_TO_RUN * 1000 ))

client() { # prefix ledger
    local n=0 out rc t0
    while [ "$(date +%s%3N)" -lt "$until" ]; do
        n=$((n + 1)); t0=$(date +%s%3N)
        out=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$URL/topics/$topic/records" \
            -H "Record-Key: $1-$n" --data-binary "v$n")
        rc=$?
        echo "$1-$n $out $rc $t0 $(date +%s%3N)" >> "$2"
        sleep 0.02
    done
}
workers=()
for c in $(seq 1 $CLIENTS); do client "c$c" "$WORK/ledger.$c" & workers+=($!); done
for w in "${workers[@]}"; do wait "$w"; done
kill -TERM "$pid"; for _ in $(seq 1 400); do kill -0 "$pid" 2> /dev/null || break; sleep 0.1; done
cat "$WORK"/ledger.* > "$WORK/ledger"
$K/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:19092 --topic "$topic" --from-beginning \
    --timeout-ms 15000 --formatter-property print.key=true --formatter-property print.value=false 2> /dev/null \
    | sort -u > "$WORK/topic"
awk -v tf="$WORK/topic" -v t0="$started" '
    BEGIN { while ((getline k < tf) > 0) present[k] = 1 }
    { total++; st[$2]++
      if ($3 == 52 || $3 == 56) { n++; w = ($1 in present) ? "WRITTEN" : "absent"
          printf "  no answer: %s curl %s at +%d ms (took %d ms), record %s\n", $1, $3, $4 - t0, $5 - $4, w
          if ($1 in present) written++ }
      if ($2 == 200 && !($1 in present)) lie++ }
    END { s = ""; for (k in st) s = s k "x" st[k] " "
          printf "[%s] %d requests in %d s | %s| no answer: %d, of which written: %d | 200 missing from the topic: %d\n",
                 build, total, secs, s, n + 0, written + 0, lie + 0 }' build="$1" secs="$SECONDS_TO_RUN" "$WORK/ledger"
grep -v -E '^%[0-9]|^configured|Application started|Responding at|COMPLETED|^SLF4J|^\[INFO\]' "$WORK/mostik.log" | head -5
echo "[$1] work: $WORK"
