#!/usr/bin/env bash
# B-05 end to end: the two answers of the deadline, each decided by reading the topic afterwards.
#
#   ci/b-05/run.sh native|jvm
#
# queued, broker silent  warm the topic's metadata, pause the broker, publish: 504 within the deadline; resume, and
#                        the record IS in the topic. That is what makes "unknown" the true word.
# queue full             pause the broker, fill the producer's queue with requests that will each end in 504, then
#                        publish once more: 429 after the queue wait; resume, and that record is NOT in the topic.
#
# The queue's bound is a platform key, so each build is given its own: librdkafka counts messages, the Java client
# counts bytes (kafkakn B-74 measured the same two).
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BROKER="$ROOT/ci/broker/broker.sh"
PORT=18096
URL="http://127.0.0.1:$PORT"
DEADLINE_MS=3000
QUEUE_WAIT_MS=1000
# How late a 504 may come and still be "within the deadline": the request's own overhead, not a second deadline.
MARGIN_MS=500
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe"); BOUND=(KAFKA_QUEUE_BUFFERING_MAX_MESSAGES=10) ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution"); BOUND=(KAFKA_BUFFER_MEMORY=32768) ;;
  *) echo "usage: run.sh native|jvm" >&2; exit 2 ;;
esac

fail() { "$BROKER" resume > /dev/null 2>&1; echo "FAIL [$1]: $2" >&2; exit 1; }
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
publish() { # key body -> "status elapsed_ms"
    local t0; t0=$(now_ms)
    local status; status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/topics/orders/records" -H "Record-Key: $1" --data-binary "$2")
    echo "$status $(( $(now_ms) - t0 ))"
}
landed() { # key -> seconds to wait for it; prints how many records carry it
    local n=0
    for _ in $(seq 1 "$2"); do
        n=$(READ_TIMEOUT_MS=3000 "$BROKER" key orders "$1" | grep -c .)
        [ "$n" -gt 0 ] && break
    done
    echo "$n"
}

"$BROKER" up > /dev/null || fail "$1" "the broker did not come up"
env MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders \
    MOSTIK_PUBLISH_DEADLINE_MS=$DEADLINE_MS MOSTIK_QUEUE_WAIT_MS=$QUEUE_WAIT_MS "${BOUND[@]}" \
    "${LAUNCH[@]}" > "/tmp/mostik-b05-$1.log" 2>&1 &
PID=$!
trap '"$BROKER" resume > /dev/null 2>&1; kill -TERM $PID 2> /dev/null; wait $PID 2> /dev/null' EXIT
for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done
run=$(date +%s%N)

read -r status _ <<< "$(publish "warm-$run" warm)"
[ "$status" = 200 ] || fail "$1" "the warm-up publish answered $status"

# 1. Queued, broker silent.
"$BROKER" pause > /dev/null
read -r status took <<< "$(publish "silent-$run" '{"orderId":1042}')"
[ "$status" = 504 ] || fail "$1" "silent broker answered $status after ${took}ms"
[ "$took" -le $(( DEADLINE_MS + MARGIN_MS )) ] || fail "$1" "504 came after ${took}ms, deadline ${DEADLINE_MS}ms"
"$BROKER" resume > /dev/null
n=$(landed "silent-$run" 10)
[ "$n" = 1 ] || fail "$1" "the 504 record is in the topic $n times after resume, expected once"
echo "[$1] queued, broker silent: 504 after ${took}ms (deadline ${DEADLINE_MS}ms); after resume the record IS in the topic"

# 2. Queue full.
"$BROKER" pause > /dev/null
filler=$(head -c 1024 /dev/zero | tr '\0' f)
for i in $(seq 1 60); do publish "fill-$run-$i" "$filler" > "/tmp/mostik-b05-$1-fill-$i" & done
sleep 1
read -r status took <<< "$(publish "full-$run" '{"orderId":1043}')"
[ "$status" = 429 ] || fail "$1" "a publish into a full queue answered $status after ${took}ms"
wait_fill=$(jobs -p); for j in $wait_fill; do [ "$j" = "$PID" ] || wait "$j" 2> /dev/null; done
fillers=$(cat /tmp/mostik-b05-"$1"-fill-* | awk '{print $1}' | sort | uniq -c | tr '\n' ' ')
"$BROKER" resume > /dev/null
sleep 5
n=$(READ_TIMEOUT_MS=5000 "$BROKER" key orders "full-$run" | grep -c .)
[ "$n" = 0 ] || fail "$1" "the 429 record is in the topic $n times"
echo "[$1] queue full: 429 after ${took}ms (queue wait ${QUEUE_WAIT_MS}ms); fillers answered: $fillers; after resume the 429 record is NOT in the topic"
echo "[$1] PASS"
