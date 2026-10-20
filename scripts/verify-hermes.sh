#!/system/bin/sh
# 验证 proot Debian 里的 Hermes
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp
exec $BASE/proot -0 -r $BASE/rootfs-deb \
  -b /dev -b /proc -b /sys -b $BASE/tmp:/tmp -w /root \
  /bin/sh -c '
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    export HOME=/root
    echo "=== which/version ==="
    which hermes; hermes --version 2>&1 | head -3
    echo "=== doctor ==="
    timeout 90 hermes doctor 2>&1 | tail -45
  '
