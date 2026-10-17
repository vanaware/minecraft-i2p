#!/usr/bin/env bash
# ====================================================================
# minecraft-i2p regression tests
#
# Usage:
#   bash tests/run-tests.sh          # offline suite (seconds, no router)
#   bash tests/run-tests.sh --live   # + full lifecycle (2-5 min)
#
# Offline suite exercises: argument parsing/rejection, --check,
# --print-address, config validation paths. Nothing starts a router.
#
# The --live suite additionally: boots a server-mode first run in a
# pristine home, waits for the printed .b32.i2p address, stops the
# process with SIGTERM (same shutdown-hook path as Ctrl+C), and
# verifies --print-address/--check agree with the live run. Works
# without internet access — the router may fail to join I2P, but the
# key/address are generated regardless.
#
# Requires: Java 17+ on PATH, a built fat JAR (dist/ or target/).
# Exit code: 0 = all passed, 1 = at least one failure.
# ====================================================================
set -u
cd "$(dirname "$0")/.." || exit 1

# ---------- locate the JAR (override with JAR=path ./tests/run-tests.sh) ----------
JAR=${JAR:-}
if [ -z "$JAR" ]; then
  [ -f dist/minecraft-i2p.jar ] && JAR=dist/minecraft-i2p.jar
  if [ -z "$JAR" ]; then
    JAR=$(ls -1 target/minecraft-i2p-*.jar 2>/dev/null | grep -v original | tail -1)
  fi
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "ERROR: no fat JAR found; build first (mvn package) or set JAR=..."; exit 1; }

LIVE=0
[ "${1:-}" = "--live" ] && LIVE=1

TH=$(mktemp -d)
trap 'rm -rf "$TH"' EXIT
PASS=0; FAIL=0; SKIP=0

run() { java -Duser.home="$TH" -jar "$JAR" "$@" 2>&1; }   # capture output

t() { # t <name> <expected_rc> <cmd args...>
  local name=$1 exp=$2; shift 2
  local out rc
  out=$(run "$@"); rc=$?
  if [ "$rc" = "$exp" ]; then
    echo "PASS  $name (rc=$rc)"; PASS=$((PASS+1))
  else
    echo "FAIL  $name (expected rc=$exp, got $rc)"
    echo "$out" | head -6 | sed 's/^/      | /'
    FAIL=$((FAIL+1))
  fi
}

check_contains() { # check_contains <name> <pattern> <cmd args...>
  local name=$1 pat=$2; shift 2
  local out
  out=$(run "$@")
  if grep -q -- "$pat" <<<"$out"; then
    echo "PASS  $name"; PASS=$((PASS+1))
  else
    echo "FAIL  $name (pattern not found: $pat)"
    echo "$out" | head -8 | sed 's/^/      | /'
    FAIL=$((FAIL+1))
  fi
}

ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }

# Any address with a valid 52-char base32 format (content is not resolved
# by --check, only its syntax) — safe as a fixed constant.
ADDR=x2nksxaqrgb67a47xj6h3n5lun7g5supqhx6ts5o2j7u3n3gagua.b32.i2p

echo "=== minecraft-i2p tests ==="
echo "JAR  : $JAR"
echo "HOME : $TH (temporary)"
echo "MODE : $([ $LIVE = 1 ] && echo 'offline + live' || echo 'offline only (use --live for lifecycle)')"
echo

# ====================================================================
# GROUP 1 — argument handling (offline, fast)
# ====================================================================
echo '----- group 1: argument handling -----'
t   help-exit0              0 --help
t   unknown-arg-exit1       1 --frobnicate
t   bad-mode-exit1          1 --mode=bogus
t   dest-in-server-exit1    1 --mode=server --dest=$ADDR
t   both-dest-exit1         1 --dest=$ADDR --dest-override=$ADDR
t   check-plus-addr-exit1   1 --check --print-address
t   bad-dest-format-exit1   1 --dest=not_valid.b32.i2p
check_contains unknown-arg-message 'Unrecognized option' --frobnicate
check_contains help-has-new-flags  'print-address'      --help
echo

# ====================================================================
# GROUP 2 — --check / --print-address with crafted configs
# ====================================================================
echo '----- group 2: offline commands -----'
mkdir -p "$TH/.minecraft-i2p"
CFG="$TH/.minecraft-i2p/minecraft-i2p.properties"

t   check-no-config-exit1   1 --check
t   addr-no-config-exit1    1 --print-address

cat > "$CFG" <<EOF
host = 127.0.0.1
port = 25565
destination = REPLACE_WITH_YOUR_ADDRESS.b32.i2p
keyFile = $TH/.minecraft-i2p/minecraft-server.dat
EOF
t   check-placeholder-exit1 1 --mode=client --check
check_contains placeholder-hint 'still the placeholder' --mode=client --check

sed -i "s|destination = .*|destination = $ADDR|" "$CFG"
t   check-valid-client-0    0 --mode=client --check

sed -i "s|destination = .*|destination = x.b32.i2p|" "$CFG"
t   check-malformed-exit1   1 --mode=client --check

sed -i "s|destination = .*|destination = $ADDR|" "$CFG"
sed -i "s|port = .*|port = 99999|" "$CFG"
t   check-bad-port-exit1    1 --mode=client --check
sed -i "s|port = .*|port = 25565|" "$CFG"

t   check-server-nokey-0    0 --mode=server --check

# stdout contract: --print-address must emit ONLY the address line
# (needs a real key file — verifiable offline only if it exists;
# the --live run generates one and always checks this).
if [ -f "$TH/.minecraft-i2p/minecraft-server.dat" ]; then
  OUT=$(java -Duser.home="$TH" -jar "$JAR" --print-address 2>/dev/null)
  [ "$OUT" = "$(java -Duser.home="$TH" -jar "$JAR" --mode=server --check 2>/dev/null | sed -n 's/.*address = //p')" ] \
    && ok "print-address-stdout-clean" || bad "print-address-stdout-clean"
else
  skip "print-address-stdout-clean (no key file; covered by --live)"
fi
echo

# ====================================================================
# GROUP 3 — live lifecycle (only with --live)
# ====================================================================
if [ $LIVE = 1 ]; then
  echo '----- group 3: live lifecycle -----'
  LOG=$TH/server-out.log

  # Pristine home: the first run must GENERATE its own config (placeholder
  # destination), so later assertions test the real generated file rather
  # than the crafted one from group 2.
  rm -rf "$TH"; mkdir -p "$TH"

  # Non-interactive scripts background children with SIGINT ignored,
  # so we stop the app with SIGTERM instead — the JVM runs the same
  # shutdown hooks for TERM that Ctrl+C triggers for INT.
  java -Duser.home="$TH" --enable-native-access=ALL-UNNAMED \
       -jar "$JAR" --mode=server > "$LOG" 2>&1 &
  SPID=$!

  LADDR=""
  for _ in $(seq 1 50); do
    sleep 5
    LADDR=$(grep -oE '[a-z2-7]{52}\.b32\.i2p' "$LOG" | head -1)
    [ -n "$LADDR" ] && break
  done
  if [ -n "$LADDR" ]; then
    ok "first-run-address-printed ($LADDR)"
  else
    bad "first-run-address-printed (no address after 250s)"
    head -20 "$LOG" | sed 's/^/      | /'
  fi

  kill -TERM $SPID 2>/dev/null
  DEAD=0
  for _ in $(seq 1 30); do
    kill -0 $SPID 2>/dev/null || { DEAD=1; break; }
    sleep 2
  done
  if [ $DEAD = 1 ]; then
    kill -0 $SPID 2>/dev/null && kill -9 $SPID 2>/dev/null
    ok "graceful-exit-after-SIGTERM"
  else
    kill -9 $SPID 2>/dev/null
    bad "graceful-exit-after-SIGTERM (survived 60s)"
  fi

  # The captured log must show the complete [SHUTDOWN] sequence
  # (check_contains always re-runs the app; grep the captured log instead).
  if grep -q '\[SHUTDOWN\] Done\.' "$LOG"; then
    ok "shutdown-sequence-complete"
  else
    bad "shutdown-sequence-complete (no [SHUTDOWN] Done. in log)"
  fi

  # Offline derivation must exactly reproduce the live-run address
  OUT=$(java -Duser.home="$TH" -jar "$JAR" --print-address 2>/dev/null)
  if [ "$OUT" = "$LADDR" ]; then
    ok "print-address-matches-live-run"
  else
    bad "print-address-matches-live-run (got '$OUT', expected '$LADDR')"
  fi

  # Checks against the REAL generated config (not hand-written):
  t    live-check-server-0   0 --mode=server --check
  t    live-check-client-1   1 --mode=client --check   # dest still placeholder
  echo
else
  echo '(group 3 skipped — rerun with --live for the full lifecycle)'
  echo
fi

# ====================================================================
echo "===== SUMMARY: $PASS passed, $FAIL failed, $SKIP skipped ====="
[ $FAIL -eq 0 ]
