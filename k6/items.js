// The scenario both binaries are driven with — for the parity smoke (B-05) and, at a fixed rate, for
// the stand measurement (B-13).
//
// It is one file for both on purpose: a parity run and a measurement that drove different traffic
// would be answering questions about different services.

import http from 'k6/http';
import { check } from 'k6';
import { normalise } from './normalise.js';

const BASE = __ENV.KEEL_BASE || 'http://127.0.0.1:8080';
const TARGET = __ENV.KEEL_TARGET || 'unknown';

// A NAMESPACE PER RUN, because the ids below are deterministic and the store has a primary key.
//
// Without it the second run against one database collides with the first on every `POST`, the route
// answers 500, and a third of the checks fail — which looks exactly like the service degrading under
// repetition. Found on the stand: run 1 passed 13 556 checks and runs 2 and 3 failed 1 886 each,
// against a service that was fine.
const RUN = __ENV.KEEL_RUN || `${Date.now()}`;

/** A measurement profile does constant work per iteration — see the note at the list call. */
const MEASURING = __ENV.KEEL_MEASURE === '1';

// Where the summary goes. `build/` is gitignored and on the mutagen session's ignore list, so what is
// written there survives; a run on another machine points this somewhere that exists.
const OUT = __ENV.KEEL_OUT || 'build/k6';

export const options = {
  scenarios: {
    items: {
      executor: __ENV.KEEL_RATE ? 'constant-arrival-rate' : 'shared-iterations',
      ...(__ENV.KEEL_RATE
        ? {
            rate: Number(__ENV.KEEL_RATE),
            timeUnit: '1s',
            duration: __ENV.KEEL_DURATION || '30s',
            preAllocatedVUs: 20,
          }
        : __ENV.KEEL_RECORD === '1'
          ? { vus: 1, iterations: 1 }
          : { vus: 4, iterations: 40 }),
    },
  },
  thresholds: { checks: ['rate==1.0'] },
};

// THE RECORDED RESPONSES ARE PRINTED, NOT COLLECTED, AND THAT IS NOT A STYLE CHOICE.
//
// The obvious shape — a module-level array pushed to from the default function and written out in
// `handleSummary` — compiles, runs, and produces an EMPTY array. k6 runs `handleSummary` in its own
// context; each VU gets its own module instance and none of them is the one the summary sees.
//
// It fails silently and in the worst possible direction: the parity comparison then diffs two empty
// files and reports no difference. That happened here, and it is why the run below asserts a
// non-zero record count before believing a clean diff.
//
// So a recording run prints one `PARITY <json>` line per call and the caller collects them.
const RECORD = __ENV.KEEL_RECORD === '1';

function record(call, response) {
  if (RECORD) console.log(`PARITY ${JSON.stringify({ call, ...normalise(response) })}`);
}

export default function () {
  // A deterministic id per iteration: the two runs have to produce the same rows, and a random id
  // would make every comparison fail for a reason that is not parity.
  //
  // The RECORDING run needs its own namespace, and finding that out cost a run. Sharing one with the
  // load pass meant the recorded `POST` hit a row the load pass had already inserted, so what got
  // compared was the primary-key violation rather than the success path — and the two drivers word
  // that differently on purpose (see below), so the comparison failed on a difference that is not a
  // defect.
  const id = RECORD ? `record-${__VU}-${__ITER}` : `${RUN}-${__VU}-${__ITER}`;

  const created = http.post(`${BASE}/items`, JSON.stringify({ id, name: `item ${id}` }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(created, { 'POST /items is 201': (r) => r.status === 201 });

  // THE LIST IS NOT IN A MEASUREMENT PROFILE, and finding that out is what the first stand run was
  // worth. `GET /items` has no limit: it returns the whole table, which a fixed-rate run grows by
  // 500 rows a second. At 500/s for 30 s the stand asked for 500 iterations a second, achieved 29.8,
  // dropped 4 692, and moved 151 MB in ten seconds — every number describing a response body that
  // was growing, not a service that was slow.
  //
  // A load profile has to do constant work per iteration or the rate is a fiction. So the list stays
  // in the parity and smoke runs, where its body is the point, and is skipped when measuring.
  if (!MEASURING) {
    const listed = http.get(`${BASE}/items`);
    check(listed, { 'GET /items is 200': (r) => r.status === 200 });
  }

  const version = http.get(`${BASE}/version`);
  check(version, { 'GET /version is 200': (r) => r.status === 200 });

  const ready = http.get(`${BASE}/health/ready`);
  check(ready, { 'ready is 200': (r) => r.status === 200 });

  // `GET /items` is deliberately not recorded: it grows as the run proceeds, so its body depends on
  // how the VUs interleaved rather than on the platform, and a comparison of it would fail for a
  // reason that is not parity. A recording run is one VU and one iteration, so the three below are
  // the same three calls on both targets.
  record('POST /items', created);
  record('GET /version', version);
  record('GET /health/ready', ready);
}

// INTO `build/`, AND NOT INTO A DIRECTORY BESIDE THIS FILE.
//
// `build/` is gitignored, which is the obvious half. The half that cost a run: this repository is
// developed against a one-way mutagen replica, and the watcher deletes anything on the Linux box that
// does not exist on the Mac — so a summary written to `k6/out/` appears and is removed between the
// run and the next command, which looks exactly like k6 failing to write it. `build/` is on the
// session's ignore list, so what is written there survives.
/** A metric field, or `?` — never an exception inside `handleSummary`. */
function num(data, metric, field) {
  const value = data.metrics?.[metric]?.values?.[field];
  return typeof value === 'number' ? value.toFixed(2) : '?';
}

export function handleSummary(data) {
  return {
    [`${OUT}/${TARGET}-summary.json`]: JSON.stringify(data.metrics, null, 2),
    // EVERY FIELD GUARDED, because one that is absent throws inside `handleSummary` and k6 reports
    // a script exception instead of a summary — the run looks like it produced nothing. `http_reqs`
    // is not in this k6's summary object under that name, and the first version of this line assumed
    // it was.
    stdout: `${TARGET}: ${num(data, 'checks', 'passes')} checks passed, ${num(data, 'checks', 'fails')} failed, ` +
      `p95 ${num(data, 'http_req_duration', 'p(95)')}ms, p99 ${num(data, 'http_req_duration', 'p(99)')}ms, ` +
      `iterations ${num(data, 'iterations', 'rate')}/s\n`,
  };
}
