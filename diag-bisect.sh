#!/usr/bin/env bash
# TEMPORARY diagnostics: find which entries of the seeded caches/modules-2/files-2.1 make
# build.gradle.kts compilation fail with "Cannot access class 'KotlinJvmProjectExtension'".
# Each probe runs `gradlew help` with a fresh GRADLE_USER_HOME holding a hard-linked subset of the seed.
set -u
seed="${MCP_DIAG_SEED:-/mnt/cache/.gradle}"
files="$seed/caches/modules-2/files-2.1"
work="${TMPDIR:-/tmp}/mcp-bisect"
here="$(cd "$(dirname "$0")" && pwd)"
runs=0

log() { echo "[MCP-BISECT] $*" >&2; }

# probe <units file>: returns 0 when the KotlinJvmProjectExtension failure reproduces, 1 otherwise
probe() {
    runs=$((runs + 1))
    rm -rf "$work"/home-*
    local h="$work/home-$runs"
    mkdir -p "$h/caches/modules-2/files-2.1"
    [ -d "$seed/wrapper" ] && cp -al "$seed/wrapper" "$h/wrapper"
    while IFS= read -r u; do
        [ -n "$u" ] || continue
        mkdir -p "$h/caches/modules-2/files-2.1/$(dirname "$u")"
        cp -al "$files/$u" "$h/caches/modules-2/files-2.1/$u"
    done < "$1"
    local out rc result
    out=$(cd "$here" && env -u MCP_DIAG_COPY -u MCP_DIAG_BISECT MCP_DIAG_INNER=1 MCP_DIAG_QUIET=1 GRADLE_USER_HOME="$h" \
        timeout 600 ./gradlew help -q -Dorg.gradle.daemon=false 2>&1)
    rc=$?
    if [ $rc -eq 0 ]; then
        result=pass
    elif echo "$out" | grep -q KotlinJvmProjectExtension; then
        result=FAIL
    else
        result=other
        log "run $runs: other failure rc=$rc: $(echo "$out" | grep -m3 -iE 'error|wrong|exception' | tr '\n' ' ')"
    fi
    log "run $runs: $(grep -c . "$1") units -> $result"
    [ "$result" = FAIL ]
}

# prefix_search <fixed units> <candidates> <result file>: smallest k such that fixed + candidates[1..k]
# reproduces; writes candidates[k] (an entry that is needed for the failure) into the result file
prefix_search() {
    local fixed=$1 cand=$2 res=$3 lo=1 hi mid
    hi=$(grep -c . "$cand")
    while [ "$lo" -lt "$hi" ]; do
        mid=$(((lo + hi) / 2))
        { cat "$fixed"; head -n "$mid" "$cand"; } > "$work/set"
        if probe "$work/set"; then hi=$mid; else lo=$((mid + 1)); fi
    done
    sed -n "${lo}p" "$cand" > "$res"
}

# minimize <units file> <result file>: entries of <units> that together reproduce the failure
minimize() {
    local all=$1 culprits=$2 i
    : > "$culprits"
    for i in 1 2 3 4; do
        grep -vxFf "$culprits" "$all" > "$work/cand" || true
        [ -s "$work/cand" ] || break
        prefix_search "$culprits" "$work/cand" "$work/found"
        cat "$work/found" >> "$culprits"
        log "needed: $(cat "$work/found")"
        if probe "$culprits"; then log "these alone reproduce: $(tr '\n' ' ' < "$culprits")"; return 0; fi
    done
    log "could not isolate a reproducing set within 4 rounds: $(tr '\n' ' ' < "$culprits")"
    return 1
}

mkdir -p "$work"
(cd "$files" && ls -d */*) | sort > "$work/modules"
: > "$work/none"
log "seed=$seed modules=$(grep -c . "$work/modules")"

probe "$work/none" && { log "empty cache reproduces - not a cache problem, stopping"; exit 0; }
probe "$work/modules" || { log "full files-2.1 does not reproduce, stopping"; exit 0; }

minimize "$work/modules" "$work/culprit-modules" || exit 0

# Narrow each culprit module down to version directories, keeping the other culprits in place
while IFS= read -r m; do
    grep -vxF "$m" "$work/culprit-modules" > "$work/fixed-modules" || true
    (cd "$files" && ls -d "$m"/*) | sort > "$work/versions"
    if [ "$(grep -c . "$work/versions")" -gt 1 ]; then
        cp "$work/fixed-modules" "$work/fixed"
        prefix_search "$work/fixed" "$work/versions" "$work/version"
        { cat "$work/fixed-modules"; cat "$work/version"; } > "$work/check"
        if probe "$work/check"; then log "RESULT $m -> $(cat "$work/version")"; else log "RESULT $m -> needs several versions: $(tr '\n' ' ' < "$work/versions")"; fi
    else
        log "RESULT $m -> $(cat "$work/versions")"
    fi
done < "$work/culprit-modules"

while IFS= read -r m; do
    (cd "$files" && find "$m" -type f) | sort | while IFS= read -r f; do log "file $f size=$(stat -c %s "$files/$f" 2>/dev/null || stat -f %z "$files/$f")"; done
done < "$work/culprit-modules"
log "done after $runs probes"
