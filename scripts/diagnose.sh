#!/system/bin/sh
BASE=/data/local/tmp/proot-test
export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/loader
export TMPDIR=$BASE/tmp
exec $BASE/proot --link2symlink -0 -r $BASE/rootfs-deb \
  -b /dev -b /proc -b /sys -b $BASE/tmp:/tmp -w /tmp \
  /bin/sh -c '
    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    echo "=== hardlink test (link2symlink on) ==="
    rm -f a b; touch a; ln a b 2>&1; echo "ln_exit=$?"; ls -la a b 2>&1
    echo "=== grep Hermes source for link-mode ==="
    grep -rn "UV_LINK_MODE\|link.mode\|link_mode" /root/.hermes/hermes-agent --include="*.py" 2>/dev/null | head -15
    echo "=== uv binary ==="
    find /root/.hermes -maxdepth 4 -name uv -type f 2>/dev/null | head -3
  '
