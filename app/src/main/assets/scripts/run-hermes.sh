#!/system/bin/sh
# Hermes proot launcher (generated from assets, see RootfsManager)
# Starts the gateway in the background and the WebUI in the foreground.
# Prefers nesquena/hermes-webui (native chat UI, SSE streaming); falls back to
# the built-in `hermes dashboard` (PTY/TUI) if webui is not installed.
# Base dir and port are substituted by the app (@BASE@, @PORT@).
BASE=@BASE@
PORT=@PORT@
LOG=$BASE/hermes-home/logs

export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/bin/loader
export TMPDIR=$BASE/tmp
export PROOT_TMP_DIR=$BASE/tmp

mkdir -p "$BASE/tmp" "$BASE/hermes-home" "$LOG"

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
  -b "$BASE/webui:/opt/hermes-webui" \
  -w /root \
  /bin/sh -c '
    export HOME=/root
    export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp

    [ -f /root/.hermes/.env ] && { set -a; . /root/.hermes/.env; set +a; }

    mkdir -p /root/.hermes/logs
    hermes gateway run >>/root/.hermes/logs/gateway.log 2>&1 &

    if [ -f /opt/hermes-webui/server.py ]; then
      P=$(ls -d /root/.hermes/tools/python-3.1*/bin/python3 2>/dev/null | sort -V | tail -1)
      if [ -x "$P" ]; then
        cd /opt/hermes-webui
        export PYTHONPATH=/opt/hermes-webui
        export HERMES_HOME=/root/.hermes
        export HERMES_WEBUI_AGENT_DIR=/root/.hermes/hermes-agent
        export HERMES_WEBUI_STATE_DIR=/root/.hermes/webui
        export HERMES_WEBUI_HOST=127.0.0.1
        export HERMES_WEBUI_PORT='"$PORT"'
        export HERMES_DISABLE_LAZY_INSTALLS=1
        exec "$P" server.py
      fi
    fi
    exec hermes dashboard --port '"$PORT"' --host 127.0.0.1 --no-open
  '
