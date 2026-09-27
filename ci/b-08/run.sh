#!/usr/bin/env bash
# B-08: how long a SIGTERM takes when the broker is gone and the producer holds records nobody will acknowledge.
#
#   ci/b-08/run.sh native|jvm [rounds]
#
# One round: the broker up, one publish to learn the topic's metadata, the broker STOPPED (refusing connections,
# which is not B-05's pause), five publishes that queue and then time out as 504, then SIGTERM while those records
# are still in the producer. Measured: signal to exit, the exit code, what kore's transcript says about the release
# of the producer, and whether any of the five records reached the topic once the broker is back.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BROKER="$ROOT/ci/broker/broker.sh"
PORT=18100
URL="http://127.0.0.1:$PORT"
ROUNDS=${2:-3}
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm [rounds]" >&2; exit 2 ;;
esac
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

for round in $(seq 1 "$ROUNDS"); do
    "$BROKER" up > /dev/null || { echo "the broker did not come up" >&2; exit 1; }
    log="/tmp/mostik-b08-$1-$round.log"
    env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders \
        MOSTIK_PUBLISH_DEADLINE_MS=3000 MOSTIK_QUEUE_WAIT_MS=1000 "${LAUNCH[@]}" > "$log" 2>&1 &
    pid=$!
    for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done
    run=$(date +%s%N)
    warm=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/topics/orders/records" -H "Record-Key: warm-$run" --data-binary w)

    docker stop -t 1 mostik-broker > /dev/null
    for i in 1 2 3 4 5; do
        curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL/topics/orders/records" -H "Record-Key: gone-$run-$i" \
            --data-binary g > "/tmp/mostik-b08-$1-$round-$i" &
    done
    sleep 4    # past the 3 s deadline: every request has its 504, and every record is still in the producer
    statuses=$(cat /tmp/mostik-b08-"$1"-"$round"-* | sort | uniq -c | tr -s ' \n' ' ')

    t0=$(now_ms)
    kill -TERM "$pid"
    # A bound of our own, so that a close which never returns is a measured number, not a hung run.
    for _ in $(seq 1 4000); do kill -0 "$pid" 2> /dev/null || break; sleep 0.1; done
    if kill -0 "$pid" 2> /dev/null; then
        took=">400000"; kill -KILL "$pid"; wait "$pid" 2> /dev/null; code=137
    else
        took=$(( $(now_ms) - t0 )); wait "$pid"; code=$?
    fi
    release=$(grep -E "^RELEASE_POOLS|^EXIT" "$log" | tr '\n' ';')

    "$BROKER" up > /dev/null
    landed=0
    for i in 1 2 3 4 5; do
        [ -n "$(READ_TIMEOUT_MS=3000 "$BROKER" key orders "gone-$run-$i")" ] && landed=$((landed + 1))
    done
    echo "[$1 round $round] warm=$warm publishes: $statuses| SIGTERM to exit: ${took} ms, exit $code | $release | of 5 records, $landed in the topic"
done
