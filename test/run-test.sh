#!/bin/bash
# LineageOS 测试 VM — 用 overlay 快照跑，原盘只读
set -e
T=/workspace/personal/android-hermes/test
EFI_CODE=/usr/share/AAVMF/AAVMF_CODE.fd

pkill -9 -f "[q]emu-system-aarch64.*android-hermes" 2>/dev/null || true
sleep 1

export LD_PRELOAD=/home/invince/vm/lineage/qemu-pixman-rbfix.so
export FP_SWAP=0x20020888:0x20030888
export FP_SWAP_AFTER=10

exec qemu-system-aarch64 \
  -machine virt,gic-version=3 \
  -cpu host -accel kvm \
  -m 8192 -smp 8 \
  -device virtio-blk-pci,drive=vda,bootindex=0 \
  -device virtio-blk-pci,drive=vdb,bootindex=1 \
  -device virtio-blk-pci,drive=vdc,bootindex=2 \
  -drive if=pflash,unit=1,file=$T/efi_vars.fd,format=raw \
  -drive file=$T/vda.overlay.qcow2,if=none,id=vda,discard=unmap,detect-zeroes=unmap \
  -drive file=$T/vdb.overlay.qcow2,if=none,id=vdb,discard=unmap,detect-zeroes=unmap \
  -drive file=/tmp/qemu-script/root-disk.img,if=none,id=vdc,format=raw \
  -device virtio-net-pci,netdev=net0 \
  -netdev user,id=net0,hostfwd=tcp:0.0.0.0:5555-:5555,hostfwd=tcp:0.0.0.0:5554-:5554 \
  -device usb-ehci,id=usb-bus \
  -device usb-tablet,bus=usb-bus.0 \
  -device usb-kbd,bus=usb-bus.0 \
  -device virtio-serial \
  -device virtio-rng-pci \
  -chardev socket,id=charconsole,path=/tmp/lineage-console.sock,server=on,wait=off \
  -serial chardev:charconsole \
  -device virtio-gpu-pci \
  -display none -vnc :2 \
  -drive if=pflash,unit=0,file=$EFI_CODE,file.locking=off,format=raw,readonly=on \
  -monitor unix:/tmp/lineage-mon.sock,server,nowait \
  -pidfile /tmp/lineage-qemu.pid
