#!/system/bin/sh
# One-shot: apt bootstrap + official Hermes installer, all inside proot.
# Base dir substituted by the app (@BASE@).
# NOTE: intentionally no --link2symlink: /data/local supports real hardlinks on
# the tested devices and link2symlink breaks uv hardlinks (EPERM). See docs §10.
BASE=@BASE@

export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/bin/loader
export TMPDIR=$BASE/tmp
export PROOT_TMP_DIR=$BASE/tmp

mkdir -p "$BASE/tmp" "$BASE/hermes-home"

# Android policy routing can hide the default route from non-fwmark processes
# (reproducible in QEMU/slirp VMs; harmless no-op when a main-table default
# already exists, e.g. on a real phone). Table id follows netd's 1000+ifindex.
# Note: toybox `ip -o link show up` can print nothing, so probe /sys/class/net.
if ! ip route show table main 2>/dev/null | grep -q '^default'; then
  IF=
  for CAND in eth0 wlan0 rmnet_data0 rmnet0; do
    [ -e "/sys/class/net/$CAND/ifindex" ] && { IF=$CAND; break; }
  done
  [ -z "$IF" ] && IF=$(ls /sys/class/net 2>/dev/null | grep -v '^lo$' | head -n1)
  IDX=$(cat "/sys/class/net/$IF/ifindex" 2>/dev/null)
  [ -n "$IDX" ] && ip rule add from all lookup $((1000 + IDX)) pref 100 2>/dev/null || true
fi

exec "$BASE/bin/proot" -0 -r "$BASE/rootfs" \
  -b /dev -b /proc -b /sys \
  -b "$BASE/tmp:/tmp" \
  -b "$BASE/hermes-home:/root/.hermes" \
  -w /root \
  /bin/sh -c '
    export HOME=/root
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    export DEBIAN_FRONTEND=noninteractive

    apt-get -o APT::Sandbox::User=root update || exit 1
    apt-get -o APT::Sandbox::User=root install -y curl ca-certificates git || true
    dpkg --configure -a || true

    curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
  '
