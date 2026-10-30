#!/system/bin/sh
# Runs `hermes doctor` inside proot. Pass --fix to attempt automatic repairs.
# Base dir substituted by the app (@BASE@).
BASE=@BASE@
FIX="$1"

export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/bin/loader
export TMPDIR=$BASE/tmp
export PROOT_TMP_DIR=$BASE/tmp

mkdir -p "$BASE/tmp" "$BASE/hermes-home"

exec "$BASE/bin/proot" -0 -r "$BASE/rootfs" \
  -b /dev -b /proc -b /sys \
  -b "$BASE/tmp:/tmp" \
  -b "$BASE/hermes-home:/root/.hermes" \
  -w /root \
  /bin/sh -c '
    export HOME=/root
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp

    [ -f /root/.hermes/.env ] && { set -a; . /root/.hermes/.env; set +a; }

    if ! command -v hermes >/dev/null 2>&1; then
      echo "hermes is not installed or not on PATH"
      exit 127
    fi

    hermes doctor '"$FIX"'
  '
