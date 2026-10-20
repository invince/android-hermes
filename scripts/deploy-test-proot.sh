#!/bin/bash
# 主机端：把 proot + loader + bionic 依赖 + rootfs 推到 Android 设备
# 用法: ./deploy-test-proot.sh <adb-serial> <rootfs.tgz>
set -e
SER=${1:?adb serial}
ROOTFS_TGZ=${2:?rootfs.tgz path}
HERE=$(cd "$(dirname "$0")" && pwd)
S="adb -s $SER"
B=$HERE/../test/proot-pkgs/x/data/data/com.termux/files/usr

$S shell 'mkdir -p /data/local/tmp/proot-test/lib /data/local/tmp/proot-test/tmp'
$S push $B/bin/proot                 /data/local/tmp/proot-test/proot
$S push $B/libexec/proot/loader      /data/local/tmp/proot-test/loader
$S push $B/lib/libtalloc.so.2.5.0    /data/local/tmp/proot-test/lib/libtalloc.so.2
$S push $B/lib/libandroid-shmem.so   /data/local/tmp/proot-test/lib/libandroid-shmem.so
$S push "$ROOTFS_TGZ"                /data/local/tmp/proot-test/rootfs.tgz
$S shell 'cd /data/local/tmp/proot-test && rm -rf rootfs && tar xzf rootfs.tgz && chmod 755 proot loader'
echo "deployed. run: adb -s $SER shell sh /data/local/tmp/proot-test/m0-proot-test.sh"
