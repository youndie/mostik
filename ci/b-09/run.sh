#!/usr/bin/env bash
# B-09: the SIGTERM oracle. Every answer a client got has to agree with what is in the topic afterwards.
#
#   ci/b-09/run.sh native|jvm [rounds] [control]
#
# One round: a topic of its own, 64 clients publishing unique keys as fast as they get answers, each keeping a
# ledger line per request (key, HTTP status, curl's exit code), SIGTERM at a random moment 3-8 s in, the process
# left to end itself, and then ONE read of the round's topic with the broker distribution's own consumer.
#
# Verdict per round, from the ledgers and the topic, never from mostik:
#   every 200 is in the topic                          a 200 missing is a lie
#   no 429 and no 503 is in the topic                  either one present is a lie
#   504s are counted both ways                         present or absent, both are true
#   the process ended itself (exit code is not 137)
# Reported beside the verdict, not part of it: reset connections (curl 52 or 56), requests that got no answer at
# all. B-11 measured them: connections in the listener's accept queue when the drain closes it, in the window that
# answers 503 anyway, and none with a record. The run of 2026-09-27 counted them as failures; its verdicts are in
# the B-09 item.
# curl 7 (connection refused) is a request that was never sent: the listener had closed. It is counted, not judged.
#
# With "control" as the third argument the broker is stopped 1 s before the signal: the ledger has to move (504s
# or 429s appear), and the verdict still has to hold.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BROKER="$ROOT/ci/broker/broker.sh"
PORT=18101
URL="http://127.0.0.1:$PORT"
ROUNDS=${2:-20}
CONTROL=${3:-}
CLIENTS=64
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm [rounds] [control]" >&2; exit 2 ;;
esac
K="docker exec mostik-broker /opt/kafka/bin"
WORK=$(mktemp -d /tmp/mostik-b09-XXXX)
failed=0

client() { # topic prefix ledger
    local n=0 out rc t0
    while :; do
        n=$((n + 1))
        t0=$(date +%s%3N)
        out=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$URL/topics/$1/records" \
            -H "Record-Key: $2-$n" --data-binary "v$n")
        rc=$?
        # key, status, curl's exit code, and when the request started and ended (epoch ms): B-11 reads the times.
        echo "$2-$n $out $rc $t0 $(date +%s%3N)" >> "$3"
        [ "$rc" = 7 ] && break   # refused: the listener is gone, nothing more to send
        sleep 0.02
    done
}

for round in $(seq 1 "$ROUNDS"); do
    "$BROKER" up > /dev/null || { echo "the broker did not come up" >&2; exit 1; }
    topic="b09-$1-$(date +%s%N)"
    $K/kafka-topics.sh --bootstrap-server 127.0.0.1:19092 --create --topic "$topic" --partitions 3 \
        --replication-factor 1 > /dev/null || exit 1
    log="$WORK/mostik-$round.log"
    env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS="$topic" \
        MOSTIK_PUBLISH_DEADLINE_MS=3000 MOSTIK_QUEUE_WAIT_MS=1000 "${LAUNCH[@]}" > "$log" 2>&1 &
    pid=$!
    for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done
    [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/topics/$topic/records" --data-binary warm)" = 200 ] \
        || { echo "round $round: the warm-up publish failed" >&2; exit 1; }

    ledger="$WORK/ledger-$round"
    : > "$ledger"
    workers=()
    for c in $(seq 1 $CLIENTS); do client "$topic" "c$c" "$ledger.$c" & workers+=($!); done

    delay_ms=$(( 3000 + RANDOM % 5000 ))
    sleep "$(echo "scale=3; $delay_ms / 1000" | bc)"
    if [ -n "$CONTROL" ]; then docker stop -t 1 mostik-broker > /dev/null; sleep 1; fi
    echo "$(date +%s%3N)" > "$WORK/signal-$round"
    kill -TERM "$pid"
    for _ in $(seq 1 900); do kill -0 "$pid" 2> /dev/null || break; sleep 0.1; done
    if kill -0 "$pid" 2> /dev/null; then kill -KILL "$pid"; fi
    wait "$pid"; code=$?
    for w in "${workers[@]}"; do wait "$w" 2> /dev/null; done
    cat "$ledger".* > "$ledger"

    [ -n "$CONTROL" ] && "$BROKER" up > /dev/null
    # One read of the whole round's topic. A key per line; the consumer stops once nothing arrives for 10 s.
    $K/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:19092 --topic "$topic" --from-beginning \
        --timeout-ms 10000 --formatter-property print.key=true --formatter-property print.value=false \
        2> /dev/null | sort -u > "$WORK/topic-$round"

    verdict=$(awk -v topicfile="$WORK/topic-$round" '
        BEGIN { while ((getline k < topicfile) > 0) present[k] = 1 }
        { key = $1; status = $2; rc = $3; total[status]++
          if (rc == 52 || rc == 56) reset++
          if (rc == 7) refused++
          if (status == 200 && !(key in present)) lie200++
          if ((status == 429 || status == 503) && (key in present)) lie4xx++
          if (status == 504) { if (key in present) s504in++; else s504out++ } }
        END { s = ""; for (st in total) s = s st "x" total[st] " "
              printf "%s| 504 in topic %d, absent %d | reset %d refused %d | lies: 200 missing %d, 429/503 present %d",
                     s, s504in, s504out, reset + 0, refused + 0, lie200 + 0, lie4xx + 0
              exit (lie200 + lie4xx > 0) }' "$ledger")
    lies=$?
    [ "$code" = 137 ] && lies=1
    [ "$lies" = 0 ] || failed=$((failed + 1))
    echo "[$1${CONTROL:+ control} round $round] signal at ${delay_ms}ms, exit $code | $verdict"
done
echo "[$1${CONTROL:+ control}] rounds with a disagreement between a ledger and the topic, or a SIGKILL: $failed of $ROUNDS"
[ "$failed" = 0 ]
