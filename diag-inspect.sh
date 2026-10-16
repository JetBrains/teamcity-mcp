#!/usr/bin/env bash
# TEMPORARY diagnostics: compare `gradlew help --debug` with an empty GRADLE_USER_HOME vs one holding only the seeded
# caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin/2.1.21, and dump agent facts about those files.
set -u
seed="${MCP_DIAG_SEED:-/mnt/cache/.gradle}"
unit="org.jetbrains.kotlin/kotlin-gradle-plugin/2.1.21"
files="$seed/caches/modules-2/files-2.1"
work="${TMPDIR:-/tmp}/mcp-inspect"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$work"

log() { echo "[MCP-INSPECT] $*" >&2; }
section() { log "===== $* ====="; }
dump() { while IFS= read -r line; do log "  $line"; done; }

section "agent"
{ id; umask; uname -a; df -hT "$seed" 2>&1; mount | grep -E " /mnt( |/)" ; } 2>&1 | dump

section "seed home top level"
ls -la --full-time "$seed" 2>&1 | dump
for f in "$seed/gradle.properties" "$seed/init.gradle" "$seed/init.gradle.kts"; do [ -f "$f" ] && { log "--- $f"; dump < "$f"; }; done
[ -d "$seed/init.d" ] && { log "--- $seed/init.d"; ls -la --full-time "$seed/init.d" 2>&1 | dump; for f in "$seed"/init.d/*; do [ -f "$f" ] && { log "--- $f"; dump < "$f"; }; done; }

section "trigger directory"
find "$files/$unit" "$files/org.jetbrains.kotlin/kotlin-gradle-plugin" -maxdepth 0 -printf '%M %u:%g %TY-%Tm-%Td %TT %p\n' 2>&1 | dump
find "$files/$unit" -printf '%M %u:%g %s %TY-%Tm-%Td %TT links=%n inode=%i %P\n' 2>&1 | dump
(getfattr -d -m - -R "$files/$unit" 2>&1 || log "getfattr unavailable") | dump
(lsattr -R "$files/$unit" 2>&1 || log "lsattr unavailable") | dump
find "$files/$unit" -type f -exec sha1sum {} + 2>&1 | dump

section "other KGP versions in the seed"
ls -la --full-time "$files/org.jetbrains.kotlin/kotlin-gradle-plugin" 2>&1 | dump

# probe <name> <copy trigger: yes|no>
probe() {
    local name=$1 copy=$2 h="$work/home-$1"
    mkdir -p "$h/caches/modules-2/files-2.1"
    [ -d "$seed/wrapper" ] && cp -al "$seed/wrapper" "$h/wrapper"
    if [ "$copy" = yes ]; then
        mkdir -p "$h/caches/modules-2/files-2.1/$(dirname "$unit")"
        cp -al "$files/$unit" "$h/caches/modules-2/files-2.1/$unit"
    fi
    (cd "$here" && env -u MCP_DIAG_INSPECT -u MCP_DIAG_COPY -u MCP_DIAG_BISECT MCP_DIAG_INNER=1 GRADLE_USER_HOME="$h" \
        timeout 900 ./gradlew help --debug --stacktrace -Dorg.gradle.daemon=false > "$work/$name.log" 2>&1)
    log "probe $name: exit=$? lines=$(wc -l < "$work/$name.log")"
    # keep the interesting lines, without timestamps/thread names and with the home path normalized
    grep -E 'kotlin-gradle-plugin|ocally available|Kotlin DSL|kotlin-dsl|[Ss]cript compil|compilation classpath|KotlinJvmProjectExtension|Cannot access|instrument|Transform|ExternalResource|Cached resource|checksum' "$work/$name.log" \
        | sed -E 's/^[0-9T:.+-]+ \[[A-Z]+\] \[[^]]*\] //; s#'"$h"'#<HOME>#g; s#/[0-9a-f]{32,40}/#/<HASH>/#g' \
        | sort -u > "$work/$name.filtered"
    log "probe $name: filtered lines=$(wc -l < "$work/$name.filtered")"
}

probe empty no
probe trigger yes

section "lines only in the failing (trigger) probe"
comm -13 "$work/empty.filtered" "$work/trigger.filtered" | head -n 250 | dump
section "lines only in the passing (empty) probe"
comm -23 "$work/empty.filtered" "$work/trigger.filtered" | head -n 250 | dump
section "failure details from the trigger probe"
grep -nE 'Cannot access|ScriptCompilationException|e: file' "$work/trigger.log" | head -n 20 | dump
grep -n -B3 -A25 'Cannot access class' "$work/trigger.log" | grep -vE '^\s*$' | head -n 80 | dump
