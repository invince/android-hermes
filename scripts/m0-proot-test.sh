#!/system/bin/sh
# M0: proot 可用性验证 —— 已在 LineageOS Android 16 / arm64 VM 实测通过
# 依赖物已由 scripts/deploy-test-proot.sh 推送到 /data/local/tmp/proot-test/
# 关键三件套（缺一不可）：
#   1) PROOT_LOADER  = proot 自带 ELF loader（否则 /bin/sh 的 musl/glibc interpreter 找不到）
#   2) LD_LIBRARY_PATH = libandroid-shmem.so + libtalloc.so.2（Termux proot 的 bionic 依赖）
#   3) -b /dev -b /proc -b /sys（proot 用户态重定向，不做真 mount → 无 SELinux 问题）
set -e
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp

exec $BASE/proot \
  --link2symlink \
  -0 \
  -r $BASE/rootfs \
  -b /dev -b /proc -b /sys \
  -b $BASE/tmp:/tmp \
  -w / \
  /bin/sh -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; uname -a; echo "who=$(id)"; cat /etc/os-release 2>/dev/null | head -3'
