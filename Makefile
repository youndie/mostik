# One gate, and CI runs exactly these targets.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read. So: whatever is not behind one of these targets is not a
# gate, and whatever is runs the same way in both places.
#
# `check` reads the documents — python, seconds, no JDK. `build` compiles and tests — a toolchain and
# minutes. Separate, because a contributor editing a document should not need the second, and one
# target that needed both would be one nobody ran.
#
#
# Every script defaults to `docs` in the working directory, so the variables below exist to be
# overridden rather than because anything needs them.

DOCS ?= docs
BACKLOG ?= backlog.md
# WHERE THE SIBLING REPOSITORIES ARE, for the two reports that resolve code anchors.
#
# `..` is right for this portfolio, where the checkouts sit side by side, and for CI, where the parent
# holds one directory. It is WRONG for a clone anywhere else, and a template gets cloned anywhere
# else: cloned to `/work`, `..` is `/`, and the report walks the entire filesystem — 25 seconds and
# then OOM-killed on an 8 GB machine. Found by B-08, doing exactly that.
#
# A clone that does not sit beside kore and sborka sets `REPOS=.` and gets a report about its own
# paths, with the anchors that name other repositories listed as not found — which is the truth for a
# machine that does not have them.
REPOS ?= ..
PY ?= python3
GRADLE ?= ./gradlew
# CI passes `--no-daemon`; a laptop wants the daemon. The flags are the only difference between the
# two, which is the point.
GRADLEFLAGS ?=

.PHONY: check gate report fix build help

help:
	@echo "make check   - the documentation gate: blocking, exactly what CI's check job runs"
	@echo "make build   - the code gate: blocking, exactly what CI's build job runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

# Blocking. Any of these failing means the documentation is internally inconsistent, which is a
# defect in the documentation rather than a matter of opinion.
#
# NOT here: `docs_check.py --on-main`, which makes `status: draft` an error on the default branch. It
# is off with an address — B-10 — rather than relaxed, and it is branch-specific in any case: a draft
# is legal in a pull request, where it means "this branch will make it true".
gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking, on purpose — AND THE `-` IS WHAT MAKES THAT TRUE.
#
# `bdd_report` counts scenarios; a percentage is meaningless while most scenarios are target
# behaviour. `code_anchors` cannot tell a path quoted AS OBSOLETE from a live one, and what rots lives
# in other people's repositories. Neither is a gate.
#
# They were nevertheless *run* by `check`, so a report that failed failed the gate — which is the
# opposite of what this comment claimed, and B-08 found it the way such things are found: a fresh
# clone at `/work` made `code_anchors` scan `/`, the kernel killed it, and `make check` went red on a
# repository whose documentation was entirely consistent. The `-` tells make to carry on; the reports
# still print, and what they print is still read by a person.
report:
	-$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	-$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

# The code gate, and CI's `build` job runs exactly this. One `build` for every target the project
# declares — which today is `jvm` and `linuxX64`; `linuxArm64` is behind `keel.linuxArm64` and is not
# covered by CI yet (B-15, blocked on youndie/razves#3).
#
# What a green run here does NOT cover is in CLAUDE.md rather than assumed: a Mac cannot link an ELF,
# so the native half of this only really runs on the Linux box.
build:
	$(GRADLE) build $(GRADLEFLAGS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
