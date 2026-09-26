#!/usr/bin/env bash
# B-03 end to end: a running mostik, a real broker, and the topic read by somebody else's client.
#
#   ci/b-03/run.sh native   the release executable
#   ci/b-03/run.sh jvm      the installed JVM distribution
#
# Every verdict comes from ci/broker/broker.sh reading the topic, never from mostik's answer alone: a `200` whose
# offset holds other bytes is exactly the defect this run exists to catch.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BROKER="$ROOT/ci/broker/broker.sh"
PORT=18095
URL="http://127.0.0.1:$PORT"
case "${1:-}" in
  native) LAUNCH=("$ROOT/server/build/bin/linuxX64/releaseExecutable/mostik.kexe") ;;
  jvm) LAUNCH=("$ROOT/distribution/build/install/distribution/bin/distribution") ;;
  *) echo "usage: run.sh native|jvm" >&2; exit 2 ;;
esac

fail() { echo "FAIL [$1]: $2" >&2; exit 1; }

"$BROKER" up > /dev/null || fail "$1" "the broker did not come up"

MOSTIK_PORT=$PORT MOSTIK_BOOTSTRAP_SERVERS=127.0.0.1:19092 MOSTIK_TOPICS=orders,payments MOSTIK_MAX_RECORD_BYTES=1024 \
    "${LAUNCH[@]}" > "/tmp/mostik-b03-$1.log" 2>&1 &
PID=$!
trap 'kill -TERM $PID 2> /dev/null; wait $PID 2> /dev/null' EXIT
for _ in $(seq 1 100); do curl -s -o /dev/null "$URL/health/ready" && break; sleep 0.2; done

run=$(date +%s%N)
order='{"orderId":1042,"amount":"19.90","currency":"EUR"}'

# 1. The sample order: 200, and the reader finds the same bytes, key and header at the returned place.
answer=$(curl -s -w '\n%{http_code}' -X POST "$URL/topics/orders/records" \
    -H "Record-Key: order-1042-$run" -H "Record-Header-trace-id: 7f3a9c" --data-binary "$order")
status=${answer##*$'\n'}
body=${answer%$'\n'*}
[ "$status" = 200 ] || fail "$1" "sample order answered $status: $body"
partition=$(printf '%s' "$body" | sed -E 's/.*"partition":([0-9]+).*/\1/')
offset=$(printf '%s' "$body" | sed -E 's/.*"offset":([0-9]+).*/\1/')
read=$("$BROKER" at orders "$partition" "$offset")
expected="Partition:$partition | Offset:$offset | trace-id:7f3a9c | order-1042-$run | $order"
[ "$read" = "$expected" ] || fail "$1" "at $partition/$offset the reader found [$read], expected [$expected]"
echo "[$1] 200 $body"
echo "[$1]     the reader at $partition/$offset: $read"

# 2. Record headers, as HTTP carries them: two names, one of them twice, in an order that is not alphabetical.
answer=$(curl -s -w '\n%{http_code}' -X POST "$URL/topics/payments/records" -H "Record-Key: headers-$run" \
    -H "Record-Header-Zeta: 1" -H "Record-Header-alpha: 2" -H "Record-Header-Zeta: 3" --data-binary 'h')
body=${answer%$'\n'*}
[ "${answer##*$'\n'}" = 200 ] || fail "$1" "the header order probe answered ${answer##*$'\n'}"
offset=$(printf '%s' "$body" | sed -E 's/.*"offset":([0-9]+).*/\1/')
echo "[$1]     headers sent as Zeta:1, alpha:2, Zeta:3 arrived as: $("$BROKER" at payments 0 "$offset" | awk -F ' \\| ' '{print $3}')"

# 3. Refused before the producer: a topic outside the allowlist, and a body over the limit. Neither is in any topic.
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/topics/audit/records" -H "Record-Key: audit-$run" --data-binary x)
[ "$status" = 404 ] || fail "$1" "audit answered $status"
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/topics/orders/records" -H "Record-Key: big-$run" \
    --data-binary "$(head -c 1025 /dev/zero | tr '\0' x)")
[ "$status" = 413 ] || fail "$1" "a 1025-byte body answered $status"
[ -z "$("$BROKER" key orders "big-$run")" ] || fail "$1" "the oversized record is in orders"
docker exec mostik-broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:19092 --list | grep -qx audit \
    && fail "$1" "a topic called audit exists"
echo "[$1] 404 for audit, 413 for 1025 bytes, and neither record is in any topic"
echo "[$1] PASS"
