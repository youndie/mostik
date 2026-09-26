// WHAT THE TWO TARGETS ARE ALLOWED TO DISAGREE ABOUT.
//
// Committed BEFORE the first parity run, and that ordering is the whole item. A normaliser written
// after a red run is a list of whatever happened to differ, and it absorbs the next real divergence
// without anybody noticing. Written first, it is a statement about what the two platforms may
// legitimately differ on — and anything it does not name is a diff that fails.
//
// Adding an entry here is a decision, not a fix. Each one below carries why it is legitimate; an
// entry that cannot be given such a line is a bug being normalised away.

/** Headers dropped from the comparison entirely. */
export const IGNORED_HEADERS = [
  // The engine's own banner. Both targets run Ktor CIO, but the header carries a version string that
  // a bump changes on one side of a comparison before the other.
  'server',
  // Wall-clock. Two processes started seconds apart cannot agree, and nothing about the service
  // depends on it.
  'date',
  // A body-length header is not dropped — see below. This list is only for values that cannot match.
];

/**
 * `/version` is a contract, so it is compared — except the fields that are facts about the build
 * rather than about the service.
 *
 * **These names were read out of a real response, and the first version of this list was not.** It
 * said `built_at`, `commit` and `release`; the body actually carries `version:` and `built:`. Nothing
 * failed, because both halves happen to share one generated build identity and the timestamps
 * matched — so a normaliser that normalised nothing looked correct. It would have started failing the
 * first time the two artefacts were built a second apart.
 */
export const VERSION_VOLATILE_FIELDS = ['built', 'commit', 'release'];

/**
 * The exit code is NOT normalised here, because nothing in an HTTP response carries it — it is named
 * so the next reader does not go looking.
 *
 * A clean `SIGTERM` exits `0` on Kotlin/Native and `143` on the JVM. **Both are correct**, and a
 * parity check that compared them would fail against two correct shutdowns. Whatever asserts the
 * shutdown asserts "the process ended itself and was not SIGKILLed (137)", never a specific code.
 */
export const EXIT_CODES_DIFFER_LEGITIMATELY = { native: 0, jvm: 143 };

/** A response reduced to what the two targets must agree on, byte for byte. */
export function normalise(response) {
  // SORTED, because the two engines emit headers in different orders and a comparison of serialised
  // objects would call that a difference. It is not one: a header set is unordered by definition.
  const headers = {};
  for (const key of Object.keys(response.headers)
    .map((name) => name.toLowerCase())
    .filter((key) => !IGNORED_HEADERS.includes(key))
    .sort()) {
    headers[key] = response.headers[Object.keys(response.headers).find((n) => n.toLowerCase() === key)];
  }
  const body = normaliseBody(response);

  // `content-length` COUNTS THE BODY THAT WAS SENT, not the one left after normalisation. Where a
  // line has been dropped the header no longer describes what is being compared, and keeping it
  // would fail the run for a reason that is not parity — a `commit` of `unknown` on one side and a
  // hash on the other differ in length even though the field is deliberately ignored. It survives
  // everywhere else, where it is a real part of the contract.
  if (body !== String(response.body ?? '')) delete headers['content-length'];

  return { status: response.status, headers, body };
}

function normaliseBody(response) {
  const body = response.body === null ? '' : String(response.body);
  // `/version` is `key: value` per line. Drop the volatile lines and keep the rest, so a renamed or
  // missing field still fails the comparison.
  if (body.includes('version:') && body.includes('\n')) {
    return body
      .split('\n')
      .filter((line) => !VERSION_VOLATILE_FIELDS.some((field) => line.startsWith(field + ':')))
      .join('\n');
  }
  return body;
}
