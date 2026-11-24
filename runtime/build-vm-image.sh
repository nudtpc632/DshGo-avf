#!/usr/bin/env bash
# Builds the AVF (Android Virtualization Framework) guest image for dsh-Go:
# a minimal read-only Ubuntu rootfs with Node.js + patched DSH + dsh-go-bridge,
# booted directly with root=/dev/vda1 (no initramfs, no PRoot). Reuses the pinned
# inputs and helpers of build-runtime.sh; trims pnpm/Python/git from the guest.
#
#   runtime/build-vm-image.sh            # arm64-v8a (the phone ABI)
#   COMPRESS=gz runtime/build-vm-image.sh  # faster first iteration (.tar.gz)
#
# Outputs (runtime/out-vm/, gitignored):
#   rootfs-vm-<abi>.ext4     read-only root disk image (fakeroot + mkfs.ext4 -d)
#   vm-config.json           `vm run` config (kernel path on device, root= params)
#   vm-manifest.json         versions, hashes, vsock ports, control protocol
#   build-report.json        assemble report (ELF audit, pruned files, warnings)
#   assets/runtime-avf/      the ext4 + manifest as APK assets (arm64 flavor)
#
# Guest layout added on top of the assemble archive:
#   /init                    busybox-static sh PID 1 (runtime/vm/init)
#   /opt/dsh-go/vsock-tcp-bridge  static aarch64 vsock<->TCP forwarder
#   /opt/dsh-go/vm-ctl.mjs   control channel (status/token, DSH_HOME save/restore)
# Writable state is tmpfs (/tmp, /root/.dsh); the host persists DSH_HOME over
# vsock:3081 because virtmgr opens disk fds O_RDONLY (no writable data disk).
set -euo pipefail

HERE="$(CDPATH= cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=build-runtime.sh
source "$HERE/build-runtime.sh"   # defines helpers; main() only runs when executed directly

ABI="${1:-arm64-v8a}"
[[ "$ABI" == arm64-v8a ]] || die "VM image currently supports arm64-v8a only (got $ABI)"
VM_OUT="${VM_OUT:-$HERE/out-vm}"
VM_WORK="$WORK/vm-$ABI"
VM_PORT="${VM_PORT:-3080}"        # dsh web loopback port == vsock port
VM_CTL_PORT=3081                  # vsock control port (guest TCP 13081)
VM_MEMORY_MIB="${VM_MEMORY_MIB:-512}"
GUEST_KERNEL_DEVICE="${GUEST_KERNEL_DEVICE:-/data/local/tmp/guest_kernel}"
VM_ROOTFS_DEVICE="${VM_ROOTFS_DEVICE:-/data/local/tmp/rootfs-vm-$ABI.ext4}"
# VM guest packages: no git (plugin installs are not supported in the VM yet);
# busybox-static provides the init shell/applets (no devtmpfs in the guest kernel).
UBUNTU_EXTRA="tzdata ca-certificates busybox-static"
VM_DEB_LOCK="$LOCKS/ubuntu-noble-vm-arm64.json"

for tool in fakeroot mkfs.ext4 aarch64-linux-gnu-gcc; do
  command -v "$tool" >/dev/null || die "missing host tool: $tool"
done

mkdir -p "$CACHE" "$VM_WORK" "$VM_OUT"
log "=== VM image $ABI ==="

base="$(ubuntu_base arm64)"
node="$(node_dist arm64)"
ubuntu_extra_debs arm64 "$base" "$VM_DEB_LOCK" > "$VM_WORK/extra-debs.txt"
mapfile -t extra_debs < "$VM_WORK/extra-debs.txt"
[[ ${#extra_debs[@]} -gt 0 ]] || die "Ubuntu package resolution produced nothing"

prefix="$VM_WORK/dsh-prefix"
# Dev reuse: a mirror may re-publish a tarball whose bytes no longer match the
# vetted patch chain; an already-vetted pristine prefix can be reused as-is.
if [[ -n "${VM_DSH_PREFIX:-}" ]]; then
  [[ -d "$VM_DSH_PREFIX/node_modules" ]] || die "VM_DSH_PREFIX is not an installed prefix: $VM_DSH_PREFIX"
  log "reusing vetted dsh prefix: $VM_DSH_PREFIX"
  rm -rf "$prefix"; cp -a "$VM_DSH_PREFIX" "$prefix"
else
  install_dsh arm64 "$prefix"
fi
log "patching DSH (runtime/patches, pinned for $DSH_VERSION, target android)"
"${PY[@]}" patch-dsh --prefix "$prefix" --dsh-version "$DSH_VERSION" --patches "$HERE/patches" \
  --target android --out "$VM_WORK/dsh-patches.json" >&2
bridge="$HERE/bridge/dsh-go-bridge"
bridge_version="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$bridge/package.json")"

# guest vsock<->TCP bridge (static, no guest libc dependency)
log "compiling vsock-tcp-bridge (static aarch64)"
aarch64-linux-gnu-gcc -static -O2 -o "$VM_WORK/vsock-tcp-bridge" "$HERE/vm/vsock-tcp-bridge.c"

# runtimeVersion: same recipe as build_abi, over the VM's own input set
lockfile="$LOCKS/dsh-$DSH_VERSION/package-lock.json"
inputs_sha="$( {
    echo "$RUNTIME_REVISION vm $ABI $DSH_VERSION $NODE_VERSION $UBUNTU_VERSION $COMPRESS $PRUNE_DEV_METADATA $SOURCE_DATE_EPOCH $UBUNTU_EXTRA"
    sha256 "$base"; sha256 "$node"; sha256sum "${extra_debs[@]}" | cut -d' ' -f1
    sha256 "$lockfile"; sha256 "$HERE/build-runtime.sh"; sha256 "$HERE/build-vm-image.sh"; sha256 "$HERE/lib/rtbuild.py"
    sha256 "$HERE/vm/init"; sha256 "$HERE/vm/vm-ctl.mjs"; sha256 "$HERE/vm/vsock-tcp-bridge.c"
    sha256 "$HERE/../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/PhoneGuest.kt"
    (cd "$HERE" && find patches bridge boot direct session-deletion -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum)
  } | sha256sum | cut -c1-12 )"
runtime_version="${RUNTIME_REVISION}-dsh${DSH_VERSION}-node${NODE_VERSION}-ubuntu${UBUNTU_VERSION}-vm${inputs_sha}"
runtime_info="$(printf '{"runtimeVersion":"%s","abi":"%s","dshVersion":"%s","nodeVersion":"%s","ubuntuVersion":"%s","dshPatches":%s,"dshGoBridge":"%s"}' \
  "$runtime_version" "$ABI" "$DSH_VERSION" "$NODE_VERSION" "$UBUNTU_VERSION" \
  "$(cat "$VM_WORK/dsh-patches.json")" "$bridge_version")"

# Static phone files (relay/overlay) baked into the ro image; the per-launch
# bridge.json is restored by the App over vm-ctl (PhoneGuest.vmFiles is the source).
PHONE_DIR="$VM_WORK/phone-files"
mkdir -p "$PHONE_DIR"
python3 -I - "$HERE/../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/PhoneGuest.kt" "$PHONE_DIR" <<'PYEOF'
import re, sys, os
src = open(sys.argv[1], encoding="utf-8").read()
out = sys.argv[2]
def raw(name):
    m = re.search(name + r' = """(.*?)"""', src, re.S)
    return m.group(1)
relay = raw("RELAY_JS")
patch = raw("PATCH_YAML").replace("$SERVER_NAME", "phone").replace("$RELAY", "/opt/dsh-app/phone/relay.cjs").replace("$CONFIG", "/var/lib/dsh-app/phone/bridge.json").replace("$TOOL_CALL_TIMEOUT_MS", "660000")
policy = raw("DIAGNOSTICS_POLICY_JS")
preset = raw("DIAGNOSTICS_PRESET_YAML")
os.makedirs(out, exist_ok=True)
open(os.path.join(out, "relay.cjs"), "w", encoding="utf-8").write(relay)
open(os.path.join(out, "diagnostics-policy.mjs"), "w", encoding="utf-8").write(policy)
open(os.path.join(out, "phone.patch.yml"), "w", encoding="utf-8").write(patch + preset)
print("phone files extracted")
PYEOF

archive="$VM_WORK/rootfs-vm-$ABI.tar.$COMPRESS"
prune=(); [[ "$PRUNE_DEV_METADATA" == 1 ]] && prune=(--prune-dev-metadata)
log "assembling rootfs ($COMPRESS; no pnpm, no python)"
"${PY[@]}" assemble --abi "$ABI" --base "$base" --node "$node" --dsh-prefix "$prefix" \
  --out "$archive" --report "$VM_OUT/build-report.json" --runtime-info "$runtime_info" \
  --compress "$COMPRESS" --xz-preset "$XZ_PRESET" --threads "$XZ_THREADS" --epoch "$SOURCE_DATE_EPOCH" "${prune[@]}" \
  "${extra_debs[@]/#/--deb=}" --bridge "$bridge"

# ext4 image: fakeroot keeps uid/gid 0 and modes from the tar (host user is not root).
# Mount points must exist in the ro root: /proc /sys /dev /tmp /root/.dsh come from
# the base/overlay; /dev/shm is created by init on the /dev tmpfs.
staging="$VM_WORK/staging"
img="$VM_OUT/rootfs-vm-$ABI.ext4"
log "building ext4 image (fakeroot tar -> mkfs.ext4 -d)"
rm -rf "$staging"; mkdir -p "$staging"
fakeroot sh -c '
  set -e
  tar -xf "$1" -C "$2"
  cp "$3" "$2/init"; chmod 0755 "$2/init"
  cp "$4" "$2/opt/dsh-go/vsock-tcp-bridge"; chmod 0755 "$2/opt/dsh-go/vsock-tcp-bridge"
  cp "$5" "$2/opt/dsh-go/vm-ctl.mjs"; chmod 0644 "$2/opt/dsh-go/vm-ctl.mjs"
  mkdir -p "$2/opt/dsh-app/phone" "$2/var/lib/dsh-app/phone"
  cp "$7/relay.cjs" "$7/diagnostics-policy.mjs" "$7/phone.patch.yml" "$2/opt/dsh-app/phone/"
  chmod 0644 "$2/opt/dsh-app/phone/"*
  chmod 0700 "$2/var/lib/dsh-app/phone"
  # size: unpacked bytes + 25% headroom (ext4 metadata, journal, inode tables)
  blocks=$(( ($(du -sb "$2" | cut -f1) * 5 / 4 + 4095) / 4096 ))
  mkfs.ext4 -q -F -d "$2" -b 4096 -m 0 "$6" "$blocks"
' sh "$archive" "$staging" "$HERE/vm/init" "$VM_WORK/vsock-tcp-bridge" "$HERE/vm/vm-ctl.mjs" "$img" "$PHONE_DIR"

img_sha="$(sha256 "$img")"
img_bytes="$(stat -c %s "$img")"
python3 -I - "$VM_OUT/vm-config.json" "$GUEST_KERNEL_DEVICE" "$VM_ROOTFS_DEVICE" "$VM_MEMORY_MIB" <<'PYEOF'
import json, sys
out, kernel, rootfs, mem = sys.argv[1:5]
cfg = {
    "kernel": kernel,
    "params": "earlycon=uart8250,mmio,0x3f8 console=hvc0 root=/dev/vda1 ro rootwait init=/init",
    "memory_mib": int(mem),
    "platform_version": "1.0",
    "disks": [{"partitions": [{"label": "rootfs", "path": rootfs}], "writable": False}],
}
json.dump(cfg, open(out, "w"), indent=2)
print("wrote " + out)
PYEOF

python3 -I - "$VM_OUT/vm-manifest.json" "$VM_OUT/build-report.json" <<PYEOF
import json, sys
out, report_path = sys.argv[1:3]
report = json.load(open(report_path))
m = {
    "schema": 1,
    "kind": "avf-vm",
    "runtimeVersion": "$runtime_version",
    "abi": "$ABI",
    "rootfs": "$(basename "$img")",
    "rootfsSha256": "$img_sha",
    "rootfsBytes": $img_bytes,
    "uncompressedBytes": report["uncompressedBytes"],
    "entries": report["counts"],
    "upgrade": report.get("upgrade"),
    "dshVersion": "$DSH_VERSION",
    "nodeVersion": "$NODE_VERSION",
    "ubuntuVersion": "$UBUNTU_VERSION",
    "dshPatches": $(cat "$VM_WORK/dsh-patches.json"),
    "dshGoBridge": {"version": "$bridge_version", "path": "/opt/dsh-go/dsh-go-bridge"},
    "boot": {
        "kernel": "$GUEST_KERNEL_DEVICE",
        "params": "earlycon=uart8250,mmio,0x3f8 console=hvc0 root=/dev/vda1 ro rootwait init=/init",
        "memoryMib": $VM_MEMORY_MIB,
        "rootWritable": False,
        "writableTmpfs": ["/tmp", "/dev", "/dev/shm", "/root/.dsh"],
    },
    "vsock": {
        "transportContract": "after writing the request the client MUST NOT shutdown(SHUT_WR); read the response until the peer closes. Guest-kernel vsock drops in-flight data after a write-half-close (small responses race, large responses almost always lost); both servers close their side after responding, so half-close is never needed.",
        "http": {"port": $VM_PORT, "guestTcp": "127.0.0.1:$VM_PORT",
                 "note": "host connects AF_VSOCK cid:port; HTTP as on the PRoot loopback"},
        "control": {"port": $VM_CTL_PORT, "guestTcp": "127.0.0.1:13081",
                    "protocol": "one JSON request line per connection, one JSON response line",
                    "commands": ["status", "save", "restore"],
                    "note": "status reports the dsh web token line; save/restore exchange DSH_HOME regular files as base64; restore also accepts @phone/bridge.json (per-launch phone config, tmpfs)"},
        "egress": {"port": 3082, "guestTcp": "127.0.0.1:13082",
                   "protocol": "minimal HTTP proxy: CONNECT host:port and absolute-form HTTP; the App listener dials the real target",
                   "note": "no virtio-net on the custom-VM path; guest HTTP(S)_PROXY points at the guest proxy (vsock-tcp-bridge -c), which tunnels over this vsock port"},
        "phone": {"port": 3083, "direction": "guest->host",
                  "note": "guest relay dials 127.0.0.1:13083 (vsock-tcp-bridge -c tunnels to vsock host:3083); App PhoneVsockListener accepts and pumps into the phone bridge loopback TCP; hello token unchanged"},
    },
    "tokenLine": {"stream": "serial console + /tmp/dsh-web.log",
                  "regex": r"^dsh web: (http://127\.0\.0\.1:[0-9]+/)\?token=([A-Za-z0-9_-]+)"},
    "build": {"inputsSha256_12": "$inputs_sha", "ubuntuBaseSha256": "$(sha256 "$base")",
              "nodeSha256": "$(sha256 "$node")", "dshLockSha256": "$(sha256 "$lockfile")",
              "sourceDateEpoch": $SOURCE_DATE_EPOCH},
}
json.dump(m, open(out, "w"), indent=2)
print("wrote " + out)
PYEOF

# APK assets for the app-side installer (AvfImage): the arm64 flavor picks up
# out-vm/assets via build.gradle.kts. The ext4 must stay uncompressed in the APK
# (androidResources.noCompress) so assets.openFd() can report its length.
mkdir -p "$VM_OUT/assets/runtime-avf"
cp -f "$img" "$VM_OUT/assets/runtime-avf/$(basename "$img")"
cp -f "$VM_OUT/vm-manifest.json" "$VM_OUT/assets/runtime-avf/vm-manifest.json"

log "VM image done: $img ($(du -h "$img" | cut -f1)), version $runtime_version"
log "push to device: adb push $img $VM_ROOTFS_DEVICE && adb push $VM_OUT/vm-config.json /data/local/tmp/"
log "run: adb shell 'cd /data/local/tmp && vm run --name dsh-go -m $VM_MEMORY_MIB --debug full --console /data/local/tmp/vm-console.log vm-config.json'"