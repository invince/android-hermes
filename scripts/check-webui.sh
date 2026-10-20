#!/system/bin/sh
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp
exec $BASE/proot -0 -r $BASE/rootfs-deb \
  -b /dev -b /proc -b /sys -b $BASE/tmp:/tmp -w /root \
  /bin/sh -c '
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp; export HOME=/root
    set -a; . /root/.hermes/.env; set +a
    echo "=== hermes --help (web/dash/gateway) ==="
    hermes --help 2>&1 | grep -iE "dashboard|webui|gateway|serve|web |http" | head -15
    echo "=== dashboard --help ==="
    hermes dashboard --help 2>&1 | head -25
  '
