#!/system/bin/sh
# Prints `hermes --version`; exit 0 only when Hermes responds.
# Base dir substituted by the app (@BASE@).
BASE=@BASE@

export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/bin/loader
export TMPDIR=$BASE/tmp
export PROOT_TMP_DIR=$BASE/tmp

exec "$BASE/bin/proot" -0 -r "$BASE/rootfs" \
  -b /dev -b /proc -b /sys \
  -b "$BASE/tmp:/tmp" \
  -b "$BASE/hermes-home:/root/.hermes" \
  -w /root \
  /bin/sh -c '
    export HOME=/root
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    hermes --version
  '
