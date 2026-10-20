#!/system/bin/sh
# 后台起 hermes dashboard（webui）在 proot Debian 里
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
    set -a; . /root/.hermes/.env; set +a
    exec hermes dashboard --port 8787 --host 127.0.0.1 --no-open
  '
