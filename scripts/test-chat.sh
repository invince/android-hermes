#!/system/bin/sh
# 配 deepseek key + 对话冒烟测试
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
    echo "=== configure provider ==="
    hermes config set model.provider deepseek 2>&1 | tail -2
    hermes config set model.default deepseek-chat 2>&1 | tail -2
    echo "=== chat smoke test ==="
    timeout 150 hermes chat -q "Reply with exactly: PONG" 2>&1 | tail -25
  '
