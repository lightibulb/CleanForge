#!/usr/bin/env bash
# =============================================================================
#  dump2txt — flatten a project directory into one .txt/.md for LLM input
#
#  Version:  1.0.0
#  License:  MIT
#  Requires: bash >= 4.4, coreutils (find, grep, sort, wc, awk, sed, tr)
#  Optional: git (for .gitignore-aware file listing)
#
#  Behaviour summary:
#    1. Enumerate files (git ls-files if in a repo, else find walk).
#    2. Filter: skip VCS/vendor/build dirs, binaries, lockfiles, oversized
#       files, hidden entries (unless -H), and user-supplied exclude globs.
#    3. Sort (path | size | name).
#    4. Emit one file: header, ASCII tree, one fenced block per source file.
#
#  Exit codes: 0 ok | 1 error | 2 nothing to include
# =============================================================================

set -uo pipefail
shopt -s nullglob

VERSION="1.0.0"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
ROOT="."
OUT=""
OUT_FORMAT="markdown"        # markdown | plain
MAX_KB=200                   # per-file limit (0 = no limit)
TOTAL_MAX_KB=0               # total content limit (0 = no limit)
MAX_FILES=0                  # file count limit (0 = no limit)
INCLUDE_HIDDEN=0
INCLUDE_LOCKFILES=0
FOLLOW_SYMLINKS=0
DRY_RUN=0
VERBOSE=0
SHOW_TREE=1
SHOW_STATS=1
SORT_ORDER="path"            # path | size | name
USE_GIT="auto"               # auto | always | never
IGNORE_FILE=".dump2txtignore"
declare -a EXCLUDE_GLOBS=()
declare -a INCLUDE_ONLY_GLOBS=()

# ---------------------------------------------------------------------------
# ANSI colours (auto-off when stderr is not a tty)
# ---------------------------------------------------------------------------
if [ -t 2 ]; then
  RESET=$'\e[0m'; DIM=$'\e[2m'; BOLD=$'\e[1m'
  GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RED=$'\e[31m'; CYAN=$'\e[36m'
else
  RESET=""; DIM=""; BOLD=""; GREEN=""; YELLOW=""; RED=""; CYAN=""
fi

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------
log()  { printf '%s\n' "$*" >&2; }
vlog() { [ "$VERBOSE" -ge 1 ] && printf "${DIM}%s${RESET}\n" "$*" >&2 || true; }
warn() { printf "${YELLOW}warning:${RESET} %s\n" "$*" >&2; }
err()  { printf "${RED}error:${RESET} %s\n" "$*" >&2; }
die()  { err "$*"; exit 1; }

# ---------------------------------------------------------------------------
# Help text
# ---------------------------------------------------------------------------
usage() {
  cat <<EOF
${BOLD}dump2txt ${VERSION}${RESET} — flatten a project into a single .txt/.md for LLM use

${BOLD}USAGE${RESET}
  dump2txt [OPTIONS] [DIRECTORY]

${BOLD}OPTIONS${RESET}
  -o, --out FILE            Output path (default: <dir>-dump-<timestamp>.{md,txt})
  -f, --format FORMAT       markdown | plain               (default: markdown)
  -s, --max-size KB         Max per-file size in KiB       (default: 200, 0 = off)
  -t, --total-size KB       Max total content in KiB       (default: 0 = unlimited)
  -n, --max-files N         Max number of files included   (default: 0 = unlimited)
  -H, --hidden              Include dotfiles and dot-directories
  -L, --lockfiles           Include lockfiles (package-lock.json, Cargo.lock, …)
  -S, --symlinks            Follow symbolic links
  -e, --exclude GLOB        Exclude glob, repeatable (matches relative path;
                            '*' matches '/' too, so '*.test.*' filters anywhere)
  -i, --include-only GLOB   Restrict to matching globs (repeatable)
  -T, --no-tree             Skip the file-tree section
  -N, --no-stats            Skip the closing summary section
  -r, --sort ORDER          path | size | name             (default: path)
  -g, --git MODE            auto | always | never          (default: auto)
  -I, --ignore-file PATH    Extra ignore patterns file     (default: .dump2txtignore)
  -D, --dry-run             Show what would be included; write nothing
  -v, --verbose             Verbose logging to stderr
  -V, --version             Print version and exit
  -h, --help                Show this help and exit

${BOLD}EXAMPLES${RESET}
  dump2txt
        Flatten the current directory.

  dump2txt ~/src/api -o /tmp/api.txt
        Explicit input directory and output path.

  dump2txt . -s 500 -f plain
        Allow files up to 500 KiB; plain-text output (no fenced blocks).

  dump2txt . -e '*.min.js' -e 'docs/**' -e '*.test.*'
        Exclude minified bundles, docs folder, and any test file.

  dump2txt . -i '*.py' -i '*.md'
        Only include .py and .md files (including inside subdirs).

  dump2txt . -g never -H
        Ignore git entirely, walk the filesystem, include dotfiles.

${BOLD}NOTES${RESET}
  • In a git repo, ".gitignore" is honored automatically via git ls-files.
  • Add a ".dump2txtignore" file (one glob per line, '#' for comments) to
    extend the default exclude set without touching the command line.
  • Binary files are detected (both by extension and by scanning for NUL).
  • Fences are auto-widened (``` → ````) if the content itself contains
    a triple backtick, so dumps never accidentally break Markdown.
  • Token estimate assumes ~4 chars/token (rough for English, slightly
    pessimistic for code, which trends ~3.2 chars/token).
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
need_val() { [ $# -ge 2 ] && [ -n "${2:-}" ] || die "option $1 requires a value"; }

while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)          need_val "$@"; OUT="$2"; shift 2;;
    -f|--format)       need_val "$@"; OUT_FORMAT="$2"; shift 2;;
    -s|--max-size)     need_val "$@"; MAX_KB="$2"; shift 2;;
    -t|--total-size)   need_val "$@"; TOTAL_MAX_KB="$2"; shift 2;;
    -n|--max-files)    need_val "$@"; MAX_FILES="$2"; shift 2;;
    -H|--hidden)       INCLUDE_HIDDEN=1; shift;;
    -L|--lockfiles)    INCLUDE_LOCKFILES=1; shift;;
    -S|--symlinks)     FOLLOW_SYMLINKS=1; shift;;
    -e|--exclude)      need_val "$@"; EXCLUDE_GLOBS+=("$2"); shift 2;;
    -i|--include-only) need_val "$@"; INCLUDE_ONLY_GLOBS+=("$2"); shift 2;;
    -T|--no-tree)      SHOW_TREE=0; shift;;
    -N|--no-stats)     SHOW_STATS=0; shift;;
    -r|--sort)         need_val "$@"; SORT_ORDER="$2"; shift 2;;
    -g|--git)          need_val "$@"; USE_GIT="$2"; shift 2;;
    -I|--ignore-file)  need_val "$@"; IGNORE_FILE="$2"; shift 2;;
    -D|--dry-run)      DRY_RUN=1; shift;;
    -v|--verbose)      VERBOSE=1; shift;;
    -V|--version)      echo "dump2txt $VERSION"; exit 0;;
    -h|--help)         usage; exit 0;;
    --)                shift; break;;
    -*)                die "unknown option: $1 (try --help)";;
    *)                 ROOT="$1"; shift;;
  esac
done

# ---------------------------------------------------------------------------
# Validate
# ---------------------------------------------------------------------------
[ -d "$ROOT" ] || die "not a directory: $ROOT"
case "$OUT_FORMAT" in markdown|plain) ;; *) die "bad --format: $OUT_FORMAT";; esac
case "$SORT_ORDER" in path|size|name) ;; *) die "bad --sort: $SORT_ORDER";; esac
case "$USE_GIT"    in auto|always|never) ;; *) die "bad --git: $USE_GIT";; esac
[[ "$MAX_KB"        =~ ^[0-9]+$ ]] || die "--max-size must be an integer"
[[ "$TOTAL_MAX_KB"  =~ ^[0-9]+$ ]] || die "--total-size must be an integer"
[[ "$MAX_FILES"     =~ ^[0-9]+$ ]] || die "--max-files must be an integer"

cd "$ROOT" || die "cannot cd to $ROOT"
ROOT_ABS="$PWD"

# default output path
if [ -z "$OUT" ]; then
  base=$(basename "$ROOT_ABS")
  ts=$(date +%Y%m%d-%H%M%S)
  ext=txt; [ "$OUT_FORMAT" = markdown ] && ext=md
  OUT="$ROOT_ABS/${base}-dump-${ts}.${ext}"
fi
case "$OUT" in /*) ;; *) OUT="$ROOT_ABS/$OUT";; esac
OUT_NAME=$(basename "$OUT")

vlog "root:  $ROOT_ABS"
vlog "out:   $OUT"
vlog "fmt:   $OUT_FORMAT  max-file=${MAX_KB}KiB  total=${TOTAL_MAX_KB}KiB"

# ---------------------------------------------------------------------------
# Language hint by extension
# ---------------------------------------------------------------------------
lang_for() {
  local base="${1##*/}" ext="${1##*.}"
  base="${base,,}"; ext="${ext,,}"
  case "$base" in
    dockerfile)             echo dockerfile; return;;
    makefile|gnumakefile)   echo makefile;   return;;
    cmakelists.txt)         echo cmake;      return;;
  esac
  case "$ext" in
    py|pyi)              echo python;;
    js|mjs|cjs)          echo javascript;;
    ts)                  echo typescript;;
    tsx)                 echo tsx;;     jsx)  echo jsx;;
    json|jsonc|json5)    echo json;;
    html|htm)            echo html;;    xml)  echo xml;;
    css)                 echo css;;     scss) echo scss;;
    sass)                echo sass;;    less) echo less;;
    md|mdx)              echo markdown;;
    yml|yaml)            echo yaml;;    toml) echo toml;;
    ini|cfg|conf)        echo ini;;     env)  echo dotenv;;
    sh|bash|zsh|fish)    echo bash;;
    c)                   echo c;;       h)    echo c;;
    cpp|cc|cxx|hpp|hxx)  echo cpp;;
    cs)                  echo csharp;;  java) echo java;;
    kt|kts)              echo kotlin;;  go)   echo go;;
    rs)                  echo rust;;    rb)   echo ruby;;
    php)                 echo php;;     swift) echo swift;;
    dart)                echo dart;;    lua)   echo lua;;
    r)                   echo r;;       sql)   echo sql;;
    gradle)              echo groovy;;  vue)   echo vue;;
    svelte)              echo svelte;;  astro) echo astro;;
    ex|exs)              echo elixir;;  erl)   echo erlang;;
    hs)                  echo haskell;; scala) echo scala;;
    clj|cljs)            echo clojure;; pl|pm) echo perl;;
    vim)                 echo vim;;     tex)   echo latex;;
    proto)               echo protobuf;; graphql|gql) echo graphql;;
    txt)                 echo text;;    rst)   echo rst;;
    tf|tfvars)           echo hcl;;     nix)   echo nix;;
    zig)                 echo zig;;
    *)                   echo "";;
  esac
}

# ---------------------------------------------------------------------------
# Binary detection (fast by extension + magic NUL sniff)
# ---------------------------------------------------------------------------
binary_ext() {
  case "${1,,}" in
    *.png|*.jpg|*.jpeg|*.gif|*.bmp|*.ico|*.svg|*.webp|*.tiff|*.tif|*.avif|\
    *.pdf|*.zip|*.tar|*.gz|*.tgz|*.bz2|*.xz|*.7z|*.rar|*.zst|\
    *.exe|*.dll|*.so|*.dylib|*.a|*.o|*.obj|*.class|*.jar|*.war|*.ear|\
    *.pyc|*.pyo|*.wasm|*.node|\
    *.mp3|*.mp4|*.wav|*.avi|*.mov|*.mkv|*.webm|*.flac|*.ogg|*.opus|*.m4a|\
    *.ttf|*.otf|*.woff|*.woff2|*.eot|\
    *.db|*.sqlite|*.sqlite3|*.bin|*.dat|*.iso|*.img|*.dmg|*.apk|*.aab|\
    *.psd|*.ai|*.sketch|*.fig)
      return 0;;
  esac
  return 1
}

is_binary_bytes() {
  [ -s "$1" ] || return 1                       # empty → text
  LC_ALL=C grep -Iq . "$1" 2>/dev/null && return 1   # has text → text
  return 0                                       # no text → binary
}

is_lockfile() {
  case "${1##*/}" in
    package-lock.json|yarn.lock|pnpm-lock.yaml|npm-shrinkwrap.json|\
    Cargo.lock|poetry.lock|Pipfile.lock|composer.lock|Gemfile.lock|\
    go.sum|mix.lock|pubspec.lock|Package.resolved|gradle.lockfile)
      return 0;;
  esac
  return 1
}

# ---------------------------------------------------------------------------
# Directory skip list (matched by basename in the find walk)
# ---------------------------------------------------------------------------
SKIP_DIR_NAMES=(
  .git .hg .svn .bzr
  node_modules bower_components jspm_packages vendor
  venv .venv env __pycache__ .mypy_cache .pytest_cache .ruff_cache
  .tox .eggs site-packages
  dist build target out
  .next .nuxt .svelte-kit .turbo .parcel-cache
  .idea .vscode
  .gradle .dart_tool .pub-cache
  Pods Carthage
  coverage .nyc_output .cache
  .terraform .serverless
  .expo .eas
)

# ---------------------------------------------------------------------------
# should_skip_file <relative-path>   (return 0 = skip)
# ---------------------------------------------------------------------------
should_skip_file() {
  local f="$1"
  local g

  # user excludes (glob against full relative path; '*' spans '/')
  if [ "${#EXCLUDE_GLOBS[@]}" -gt 0 ]; then
    for g in "${EXCLUDE_GLOBS[@]}"; do
      [[ "$f" == $g ]] && return 0
    done
  fi

  # include-only whitelist
  if [ "${#INCLUDE_ONLY_GLOBS[@]}" -gt 0 ]; then
    local hit=1
    for g in "${INCLUDE_ONLY_GLOBS[@]}"; do
      [[ "$f" == $g ]] && { hit=0; break; }
    done
    [ "$hit" -ne 0 ] && return 0
  fi

  # binary
  binary_ext "$f" && return 0
  is_binary_bytes "$ROOT_ABS/$f" && return 0

  # lockfile
  [ "$INCLUDE_LOCKFILES" -eq 0 ] && is_lockfile "$f" && return 0

  # hidden components (unless -H)
  if [ "$INCLUDE_HIDDEN" -eq 0 ]; then
    case "/$f/" in
      */.*) return 0;;   # some path component starts with '.'
    esac
  fi

  return 1
}

# ---------------------------------------------------------------------------
# Custom ignore file
# ---------------------------------------------------------------------------
load_ignore_file() {
  local f="$ROOT_ABS/$IGNORE_FILE"
  [ -f "$f" ] || return 0
  vlog "loading extra ignore patterns from $f"
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    [ -z "$line" ] && continue
    EXCLUDE_GLOBS+=("$line")
  done < "$f"
}

# ---------------------------------------------------------------------------
# File listing strategies
# ---------------------------------------------------------------------------
list_via_git() {
  git -C "$ROOT_ABS" ls-files -z --cached --others --exclude-standard 2>/dev/null
}

list_via_find() {
  local -a findargs=()
  [ "$FOLLOW_SYMLINKS" -eq 1 ] && findargs+=(-L)
  findargs+=("$ROOT_ABS" -mindepth 1 '(')
  local first=1 d
  for d in "${SKIP_DIR_NAMES[@]}"; do
    [ $first -eq 0 ] && findargs+=(-o)
    findargs+=(-name "$d" -type d)
    first=0
  done
  findargs+=(')' -prune -o -type f -print0)
  find "${findargs[@]}"
}

# ---------------------------------------------------------------------------
# Tree renderer (proper ├──/└── with last-child detection, via awk)
# ---------------------------------------------------------------------------
build_tree() {
  LC_ALL=C sort <<<"$(printf '%s\n' "${KEEP[@]}")" | awk -F'/' '
    {
      n = NF
      parent = ""
      for (i = 1; i <= n; i++) {
        children[parent SUBSEP $i] = 1
        full = (parent == "") ? $i : parent "/" $i
        if (i < n) isdir[full] = 1
        parent = full
      }
    }
    END { _print("", "") }
    function _print(parent, prefix,   kids, n, k, i, tmp, j, is_last, full, parts) {
      n = 0
      for (k in children) {
        split(k, parts, SUBSEP)
        if (parts[1] == parent) kids[++n] = parts[2]
      }
      for (i = 2; i <= n; i++) {
        tmp = kids[i]; j = i - 1
        while (j > 0 && kids[j] > tmp) { kids[j+1] = kids[j]; j-- }
        kids[j+1] = tmp
      }
      for (i = 1; i <= n; i++) {
        is_last = (i == n)
        full = (parent == "") ? kids[i] : parent "/" kids[i]
        print prefix (is_last ? "└── " : "├── ") kids[i] (full in isdir ? "/" : "")
        if (full in isdir) _print(full, prefix (is_last ? "    " : "│   "))
      }
    }
  '
}

# ---------------------------------------------------------------------------
# Fence auto-widening
# ---------------------------------------------------------------------------
pick_fence() {
  if grep -q '```' "$1" 2>/dev/null; then printf '````'; else printf '```'; fi
}

# ---------------------------------------------------------------------------
# Collect + filter
# ---------------------------------------------------------------------------
load_ignore_file

use_git_now=0
case "$USE_GIT" in
  always) use_git_now=1;;
  auto)
    if command -v git >/dev/null 2>&1 \
       && git -C "$ROOT_ABS" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      use_git_now=1
    fi;;
  never) ;;
esac

declare -a CANDIDATES=()
if [ "$use_git_now" -eq 1 ]; then
  vlog "enumerating via git ls-files"
  while IFS= read -r -d '' rel; do
    CANDIDATES+=("$ROOT_ABS/$rel")
  done < <(list_via_git)
else
  vlog "enumerating via find"
  while IFS= read -r -d '' abs; do
    CANDIDATES+=("$abs")
  done < <(list_via_find)
fi
vlog "candidate files: ${#CANDIDATES[@]}"

declare -a KEEP=()
skipped=0
kept_bytes=0
limit_bytes=$((MAX_KB * 1024))
total_limit=$((TOTAL_MAX_KB * 1024))

for abs in "${CANDIDATES[@]}"; do
  [ -f "$abs" ] || continue
  if [ -L "$abs" ] && [ "$FOLLOW_SYMLINKS" -eq 0 ]; then
    skipped=$((skipped+1)); continue
  fi

  rel="${abs#"$ROOT_ABS"/}"
  [ "$rel" = "$OUT_NAME" ] && continue
  [ "$abs" = "$OUT" ] && continue

  should_skip_file "$rel" && { skipped=$((skipped+1)); continue; }

  size=$(wc -c < "$abs" 2>/dev/null || echo 0)
  if [ "$limit_bytes" -gt 0 ] && [ "$size" -gt "$limit_bytes" ]; then
    vlog "skip (large):  $rel  (${size}B > ${limit_bytes}B)"
    skipped=$((skipped+1)); continue
  fi
  if [ "$total_limit" -gt 0 ] && [ $((kept_bytes + size)) -gt "$total_limit" ]; then
    vlog "skip (budget): $rel"
    skipped=$((skipped+1)); continue
  fi
  if [ "$MAX_FILES" -gt 0 ] && [ "${#KEEP[@]}" -ge "$MAX_FILES" ]; then
    vlog "skip (count):  $rel"
    skipped=$((skipped+1)); continue
  fi

  KEEP+=("$rel")
  kept_bytes=$((kept_bytes + size))
done

vlog "kept ${#KEEP[@]}, skipped $skipped, content ${kept_bytes}B"

if [ "${#KEEP[@]}" -eq 0 ]; then
  warn "no files to include — check your filters (try --dry-run --verbose)"
  exit 2
fi

# ---------------------------------------------------------------------------
# Sort
# ---------------------------------------------------------------------------
case "$SORT_ORDER" in
  path) LC_ALL=C sort -o <(printf '%s\n' "${KEEP[@]}") /dev/null 2>/dev/null || true ;;
esac

if [ "$SORT_ORDER" = "path" ]; then
  mapfile -t KEEP < <(printf '%s\n' "${KEEP[@]}" | LC_ALL=C sort)
elif [ "$SORT_ORDER" = "name" ]; then
  tmp=$(mktemp)
  for r in "${KEEP[@]}"; do printf '%s\t%s\n' "${r##*/}" "$r"; done \
    | LC_ALL=C sort -f > "$tmp"
  mapfile -t KEEP < <(cut -f2- "$tmp")
  rm -f "$tmp"
elif [ "$SORT_ORDER" = "size" ]; then
  tmp=$(mktemp)
  for r in "${KEEP[@]}"; do
    printf '%012d\t%s\n' "$(wc -c < "$ROOT_ABS/$r")" "$r"
  done | LC_ALL=C sort -rn > "$tmp"
  mapfile -t KEEP < <(cut -f2- "$tmp")
  rm -f "$tmp"
fi

# ---------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  log "${BOLD}would include ${#KEEP[@]} files:${RESET}"
  for r in "${KEEP[@]}"; do
    printf '  %9d  %s\n' "$(wc -c < "$ROOT_ABS/$r")" "$r"
  done
  log "would write: $OUT"
  exit 0
fi

# ---------------------------------------------------------------------------
# Emit
# ---------------------------------------------------------------------------
emit_file() {
  local rel="$1"
  local abs="$ROOT_ABS/$rel"
  local size; size=$(wc -c < "$abs")
  local lang; lang=$(lang_for "$rel")
  local fence; fence=$(pick_fence "$abs")

  if [ "$OUT_FORMAT" = "markdown" ]; then
    printf '\n\n---\n\n## `%s`\n\n' "$rel"
    printf '%s%s\n' "$fence" "$lang"
    cat "$abs"
    [ -n "$(tail -c1 "$abs" 2>/dev/null)" ] && printf '\n'
    printf '%s\n' "$fence"
  else
    printf '\n\n========== FILE: %s (%s bytes) ==========\n\n' "$rel" "$size"
    cat "$abs"
    [ -n "$(tail -c1 "$abs" 2>/dev/null)" ] && printf '\n'
  fi
}

emit_header() {
  local chars="$1" tokens="$2"
  if [ "$OUT_FORMAT" = "markdown" ]; then
    printf '# Project dump: %s\n\n' "$(basename "$ROOT_ABS")"
    printf -- '- Generated: `%s`\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    printf -- '- Root: `%s`\n' "$ROOT_ABS"
    if command -v git >/dev/null 2>&1 \
       && git -C "$ROOT_ABS" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      printf -- '- Git HEAD: `%s`\n' \
        "$(git -C "$ROOT_ABS" rev-parse --short HEAD 2>/dev/null || echo '?')"
    fi
    printf -- '- Files: **%d**\n' "${#KEEP[@]}"
    printf -- '- Est. tokens: **~%d**\n\n' "$tokens"
  else
    printf 'Project:  %s\n' "$(basename "$ROOT_ABS")"
    printf 'Generated: %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    printf 'Root:      %s\n' "$ROOT_ABS"
    printf 'Files:     %d\n' "${#KEEP[@]}"
    printf 'Est tok:   ~%d\n' "$tokens"
  fi
}

emit_footer() {
  local chars="$1" tokens="$2"
  if [ "$OUT_FORMAT" = "markdown" ]; then
    printf '\n\n---\n\n## Summary\n\n'
    printf -- '- Files: %d\n' "${#KEEP[@]}"
    printf -- '- Content bytes: %d\n' "$kept_bytes"
    printf -- '- Output bytes: %d\n' "$chars"
    printf -- '- Est. tokens: ~%d\n' "$tokens"
  else
    printf '\n\n=== SUMMARY ===\n'
    printf 'Files: %d\nContent: %d bytes\nOutput: %d bytes\nEst tok: ~%d\n' \
      "${#KEEP[@]}" "$kept_bytes" "$chars" "$tokens"
  fi
}

emit() {
  local body; body=$(mktemp)
  local i=0

  {
    # tree
    if [ "$SHOW_TREE" -eq 1 ]; then
      if [ "$OUT_FORMAT" = "markdown" ]; then
        printf '\n## File tree\n\n```\n'
      else
        printf '\n=== FILE TREE ===\n'
      fi
      build_tree
      if [ "$OUT_FORMAT" = "markdown" ]; then printf '```\n'; fi
    fi

    # per-file blocks
    for rel in "${KEEP[@]}"; do
      emit_file "$rel"
      i=$((i+1))
      if [ "$VERBOSE" -ge 1 ] && [ $((i % 25)) -eq 0 ]; then
        vlog "wrote $i / ${#KEEP[@]}"
      fi
    done
  } > "$body"

  local body_chars; body_chars=$(wc -c < "$body")
  local tokens=$((body_chars / 4))

  {
    emit_header "$body_chars" "$tokens"
    cat "$body"
    [ "$SHOW_STATS" -eq 1 ] && emit_footer "$body_chars" "$tokens"
  } > "$OUT"

  rm -f "$body"

  printf "${GREEN}✓${RESET} %s  |  %d files  |  %s chars (~%s tokens)\n" \
    "$OUT" "${#KEEP[@]}" "$body_chars" "$tokens"
  [ "$skipped" -gt 0 ] && \
    printf "  ${DIM}skipped %d (binary/oversized/lockfile/hidden/pattern)${RESET}\n" \
      "$skipped"
}

emit
