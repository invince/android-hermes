#!/system/bin/sh
# proot 进 Debian 装 Hermes
# 注意：不加 --link2symlink！/data/local/tmp 支持真 hardlink（实测 ln 成功），
# 加了反而把 uv 的 hardlink 模拟坏 → EPERM。
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp

exec $BASE/proot -0 -r $BASE/rootfs-deb \
  -b /dev -b /proc -b /sys -b $BASE/tmp:/tmp -w /root \
  /bin/sh -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    export DEBIAN_FRONTEND=noninteractive
    export HOME=/root
    curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
  '
