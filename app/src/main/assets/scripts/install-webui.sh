#!/system/bin/sh
# Installs nesquena/hermes-webui (native chat UI) into $BASE/webui.
# The tarball must already be at $BASE/tmp/webui.tar.gz (RootfsManager).
# Base dir substituted by the app (@BASE@).
BASE=@BASE@

export LD_LIBRARY_PATH=$BASE/lib
export PROOT_LOADER=$BASE/bin/loader
export TMPDIR=$BASE/tmp
export PROOT_TMP_DIR=$BASE/tmp

mkdir -p "$BASE/webui" "$BASE/tmp"

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

    P=$(ls -d /root/.hermes/tools/python-3.1*/bin/python3 2>/dev/null | sort -V | tail -1)
    [ -x "$P" ] || { echo "no managed python"; exit 2; }

    # server.py needs PyYAML; cryptography is only for optional passkeys/OIDC.
    pip_install() {
      "$P" -m pip install -q --disable-pip-version-check --root-user-action=ignore \
        --timeout 30 --retries 2 "$@" && return 0
      echo "default index failed, retrying with Tsinghua mirror…"
      "$P" -m pip install -q --disable-pip-version-check --root-user-action=ignore \
        --timeout 30 --retries 2 -i https://pypi.tuna.tsinghua.edu.cn/simple "$@"
    }

    if ! "$P" -c "import yaml" >/dev/null 2>&1; then
      echo "installing PyYAML…"
      pip_install pyyaml || exit 3
    fi
    if ! "$P" -c "import cryptography" >/dev/null 2>&1; then
      echo "installing cryptography (optional)…"
      pip_install cryptography || echo "cryptography install failed (passkeys disabled)"
    fi

    mkdir -p /opt/hermes-webui
    rm -rf /opt/hermes-webui/* 2>/dev/null
    tar xzf /tmp/webui.tar.gz --strip-components=1 -C /opt/hermes-webui || exit 4
    [ -f /opt/hermes-webui/server.py ] || exit 5
    echo WEBUI_OK
  '
