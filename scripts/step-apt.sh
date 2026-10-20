#!/system/bin/sh
# 以真实 root 跑 proot 进 Debian：apt update + curl/ca-certificates（修 TMPDIR）
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp          # 给 proot 自己用（主机路径）

exec $BASE/proot --link2symlink -0 -r $BASE/rootfs-deb \
  -b /dev -b /proc -b /sys -b $BASE/tmp:/tmp -w / \
  /bin/sh -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp                      # ← 给 rootfs 里的 apt/dpkg 用
    export DEBIAN_FRONTEND=noninteractive
    apt-get -o APT::Sandbox::User=root update || exit 1
    apt-get -o APT::Sandbox::User=root install -y curl ca-certificates || true
    dpkg --configure -a || true
    echo "--- verify ---"
    curl --version | head -1
    ls /etc/ssl/certs | head -3
  '
