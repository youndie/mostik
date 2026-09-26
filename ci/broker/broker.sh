#!/usr/bin/env bash
# The broker, and the reader every scenario is decided by (B-02).
#
# The reader never goes through kafkakn or mostik. A producer asked whether it delivered answers yes, and
# the only way to tell whether a `504` record was written is to read the topic with somebody else's client:
# the broker distribution's own console consumer, inside the broker's container.
#
#   broker.sh up                               start the broker, wait until it ANSWERS, create the sample topics
#   broker.sh down                             stop and remove it
#   broker.sh pause | resume                   make it silent (B-05's "broker silent"), and bring it back
#   broker.sh produce <topic> <key> <value>    write one record with the distribution's console producer
#   broker.sh at <topic> <partition> <offset>  print the record there: partition, offset, headers, key, value
#   broker.sh key <topic> <key>                print every record of the topic that carries that key
#   broker.sh selftest                         the acceptance of B-02, end to end
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE="$HERE/docker-compose.yml"
CONTAINER=mostik-broker
# Inside the container the listener is on the same port the host publishes, so one address works for both.
BOOTSTRAP=127.0.0.1:19092
BIN=/opt/kafka/bin
# How long a read waits for records that are not there. A read by key has no end marker, so it stops when
# nothing arrives for this long; a partition read stops at its one record.
READ_TIMEOUT_MS=${READ_TIMEOUT_MS:-5000}

kc() { docker exec "$CONTAINER" "$@"; }
# -i BEFORE the container name. `docker exec <name> -i` makes "-i" the command, and a producer piped into
# an exec with no stdin reads EOF, exits zero and writes nothing (kafkakn, docs/services/test-broker.md).
kci() { docker exec -i "$CONTAINER" "$@"; }

# The sample topics of the technical brief §5a: `orders` with 3 partitions and `payments` with 1. `audit`
# is deliberately NOT created: it is the topic outside the allowlist, and it must not exist here either.
TOPICS=("orders:3" "payments:1")

# Every field the scenarios compare: where the record is, its headers, its key, its value.
#
# `--formatter-property`, not `--property`: Kafka 4.3 prints its deprecation notice for `--property` on STDOUT,
# where it becomes the first line of every record read (measured 2026-09-27, the first selftest run).
PRINT=(--formatter-property print.partition=true --formatter-property print.offset=true
       --formatter-property print.headers=true --formatter-property print.key=true
       --formatter-property key.separator=' | ')

case "${1:-}" in
  up)
    # `--wait` is NOT evidence the broker is up: a crash-looping container is reported Healthy. The only
    # evidence accepted is the broker answering a request.
    docker compose -f "$COMPOSE" up -d --wait < /dev/null > /dev/null 2>&1
    answered=
    for _ in $(seq 1 30); do
        if kc $BIN/kafka-broker-api-versions.sh --bootstrap-server "$BOOTSTRAP" > /dev/null 2>&1; then
            answered=yes
            break
        fi
        sleep 2
    done
    [ -n "$answered" ] || {
        echo "BROKER DID NOT ANSWER on $BOOTSTRAP - here is why:" >&2
        docker logs "$CONTAINER" 2>&1 | grep -iE "exception|error|missing" | tail -3 >&2
        exit 1
    }
    for spec in "${TOPICS[@]}"; do
        kc $BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
            --topic "${spec%%:*}" --partitions "${spec##*:}" --replication-factor 1 > /dev/null || exit 1
    done
    echo "broker answers on $BOOTSTRAP; topics: ${TOPICS[*]}"
    ;;
  down)
    docker compose -f "$COMPOSE" down -v < /dev/null > /dev/null 2>&1
    echo "broker removed"
    ;;
  pause)
    docker pause "$CONTAINER" > /dev/null && echo "broker paused: it accepts connections and answers nothing"
    ;;
  resume)
    docker unpause "$CONTAINER" > /dev/null && echo "broker resumed"
    ;;
  produce)
    [ $# -eq 4 ] || { echo "usage: broker.sh produce <topic> <key> <value>" >&2; exit 2; }
    printf '%s\t%s\n' "$3" "$4" | kci $BIN/kafka-console-producer.sh --bootstrap-server "$BOOTSTRAP" \
        --topic "$2" --reader-property parse.key=true --reader-property key.separator=$'\t' > /dev/null
    ;;
  at)
    [ $# -eq 4 ] || { echo "usage: broker.sh at <topic> <partition> <offset>" >&2; exit 2; }
    kc $BIN/kafka-console-consumer.sh --bootstrap-server "$BOOTSTRAP" --topic "$2" \
        --partition "$3" --offset "$4" --max-messages 1 --timeout-ms "$READ_TIMEOUT_MS" "${PRINT[@]}" 2> /dev/null
    ;;
  key)
    [ $# -eq 3 ] || { echo "usage: broker.sh key <topic> <key>" >&2; exit 2; }
    # Every partition from the beginning; the filter is on the key field, which the print format puts
    # between the headers and the value.
    kc $BIN/kafka-console-consumer.sh --bootstrap-server "$BOOTSTRAP" --topic "$2" --from-beginning \
        --timeout-ms "$READ_TIMEOUT_MS" "${PRINT[@]}" 2> /dev/null \
        | awk -F ' \\| ' -v key="$3" '$4 == key'
    ;;
  selftest)
    # B-02's acceptance, and its own positive control: a record written by the distribution's producer is
    # found at its place and by its key, and a key nobody wrote is found nowhere.
    "$0" up || exit 1
    key="selftest-$(date +%s%N)"
    "$0" produce orders "$key" '{"orderId":1042,"amount":"19.90","currency":"EUR"}' || exit 1
    found=$("$0" key orders "$key")
    [ "$(printf '%s\n' "$found" | grep -c .)" = 1 ] || { echo "FAIL: by key: [$found]" >&2; exit 1; }
    partition=$(printf '%s' "$found" | awk -F ' \\| ' '{sub(/^Partition:/, "", $1); print $1}')
    offset=$(printf '%s' "$found" | awk -F ' \\| ' '{sub(/^Offset:/, "", $2); print $2}')
    at=$("$0" at orders "$partition" "$offset")
    [ "$at" = "$found" ] || { echo "FAIL: at $partition/$offset: [$at], by key: [$found]" >&2; exit 1; }
    nobody=$("$0" key orders "never-written-$key")
    [ -z "$nobody" ] || { echo "FAIL: a key nobody wrote was found: [$nobody]" >&2; exit 1; }
    echo "selftest: written and read back at partition $partition offset $offset, by key and by place:"
    echo "  $found"
    ;;
  *)
    sed -n '2,/^set -uo/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
