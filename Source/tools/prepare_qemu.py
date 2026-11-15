#!/usr/bin/env python3
"""
DroidVM native payload preparation.

Downloads the prebuilt QEMU (Android/bionic builds from the Termux package
repository), resolves the complete shared-library closure and installs it into
app/src/main/jniLibs/<abi>/ using names that Android's package manager will
extract (lib*.so). Versioned sonames (e.g. libz.so.1) are renamed and recorded in
tools/libmap.txt; tools/fix_elf.py then rewrites DT_NEEDED/DT_SONAME to those file
names and strips the Termux RUNPATH, so everything runs from nativeLibraryDir only
(no symlinks, no executables in the app data directory).

Firmware (SeaBIOS, EDK2/UEFI, option ROMs) and keymaps are copied to
app/src/main/assets/qemu/share.

Usage:  python3 tools/prepare_qemu.py            (needs: python3, ar, tar, readelf)
"""
import subprocess, sys
import os, re, sys, json, shutil, subprocess, tempfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ABIS = {"arm64-v8a": "aarch64", "x86_64": "x86_64"}
ROOT_PKGS = ["qemu-system-x86-64-headless", "qemu-system-aarch64-headless", "qemu-utils"]
EXECUTABLES = {"qemu-system-x86_64": "libqemu_system_x86_64.so",
               "qemu-system-aarch64": "libqemu_system_aarch64.so",
               "qemu-img": "libqemu_img.so"}
SKIP = {"resolv-conf", "qemu-common", "termux-tools", "dash", "bash", "coreutils", "busybox",
        "termux-am", "termux-exec", "procps", "termux-licenses", "ca-certificates",
        "openssl-tool", "glib-bin", "python", "speexdsp"}
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
               "libOpenSLES.so", "libaaudio.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
               "libmediandk.so", "libvulkan.so", "libnativewindow.so"}
FIRMWARE = ["bios-256k.bin", "bios.bin", "kvmvapic.bin", "linuxboot_dma.bin", "linuxboot.bin",
            "multiboot_dma.bin", "pvh.bin", "vgabios.bin", "vgabios-stdvga.bin",
            "vgabios-virtio.bin", "vgabios-ramfb.bin", "vgabios-bochs-display.bin",
            "vgabios-cirrus.bin", "vgabios-qxl.bin", "vgabios-vmware.bin", "efi-virtio.rom",
            "efi-e1000.rom", "efi-e1000e.rom", "efi-rtl8139.rom", "pxe-virtio.rom",
            "edk2-aarch64-code.fd", "edk2-arm-vars.fd", "edk2-x86_64-code.fd",
            "edk2-i386-vars.fd", "edk2-licenses.txt",
            "edk2-x86_64-secure-code.fd", "efi-pcnet.rom", "efi-ne2k_pci.rom", "efi-eepro100.rom",
            "efi-vmxnet3.rom", "qboot.rom", "edk2-i386-code.fd", "edk2-i386-secure-code.fd",
            "pxe-e1000.rom", "pxe-eepro100.rom", "pxe-ne2k_pci.rom", "pxe-pcnet.rom", "pxe-rtl8139.rom"]

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
JNI = os.path.join(PROJECT, "app/src/main/jniLibs")
ASSETS = os.path.join(PROJECT, "app/src/main/assets/qemu")
CACHE = os.path.join(PROJECT, "build/termux-cache")


def index(arch):
    raw = urllib.request.urlopen(f"{REPO}/dists/stable/main/binary-{arch}/Packages").read().decode()
    pk, provides = {}, {}
    for block in raw.split("\n\n"):
        d = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                d[k] = v.strip()
        if "Package" in d:
            pk[d["Package"]] = d
    for name, d in pk.items():
        for p in d.get("Provides", "").split(","):
            p = re.sub(r"\(.*\)", "", p).strip()
            if p:
                provides.setdefault(p, name)
    return pk, provides


def resolve(pk, provides):
    seen, order = set(), []
    def visit(n):
        n = n if n in pk else provides.get(n, n)
        if n in seen or n in SKIP or n not in pk:
            return
        seen.add(n); order.append(n)
        for dep in pk[n].get("Depends", "").split(","):
            dep = re.sub(r"\(.*\)", "", dep.split("|")[0]).strip()
            if dep:
                visit(dep)
    for r in ROOT_PKGS + ["dbus"]:
        visit(r)
    return order + ["qemu-common"]


def extract(arch, pk, names):
    root = os.path.join(CACHE, arch, "root")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    versions = {}
    for n in names:
        fn = pk[n]["Filename"]
        deb = os.path.join(CACHE, arch, os.path.basename(fn))
        if not os.path.exists(deb):
            urllib.request.urlretrieve(f"{REPO}/{fn}", deb)
        with tempfile.TemporaryDirectory() as t:
            subprocess.check_call(["ar", "x", deb], cwd=t)
            data = [f for f in os.listdir(t) if f.startswith("data.tar")][0]
            subprocess.check_call(["tar", "xf", os.path.join(t, data), "-C", root])
        versions[n] = pk[n]["Version"]
    return os.path.join(root, "data/data/com.termux/files/usr"), versions


def needed(f):
    out = subprocess.run(["readelf", "-d", f], capture_output=True, text=True).stdout
    return [l.split("[")[1].split("]")[0] for l in out.splitlines() if "(NEEDED)" in l]


def jni_name(soname):
    if re.fullmatch(r"lib[A-Za-z0-9_+\-]*\.so", soname) and "-" not in soname:
        return soname
    base = soname[3:] if soname.startswith("lib") else soname
    return "libdvm_" + re.sub(r"[^A-Za-z0-9]", "_", base) + ".so"


def main():
    os.makedirs(CACHE, exist_ok=True)
    report = {}
    libmap_all = {}
    usr = None
    for abi, arch in ABIS.items():
        os.makedirs(os.path.join(CACHE, arch), exist_ok=True)
        pk, provides = index(arch)
        names = resolve(pk, provides)
        usr, versions = extract(arch, pk, names)
        out = os.path.join(JNI, abi)
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out)
        libdir = os.path.join(usr, "lib")
        closure, todo, missing = {}, [os.path.join(usr, "bin", e) for e in EXECUTABLES], set()
        while todo:
            f = todo.pop()
            for n in needed(f):
                if n in SYSTEM_LIBS or n in closure:
                    continue
                p = os.path.join(libdir, n)
                if os.path.exists(p):
                    closure[n] = os.path.realpath(p); todo.append(p)
                else:
                    missing.add(n)
        if missing:
            sys.exit(f"[{abi}] unresolved libraries: {missing}")
        libmap = {}
        for so, path in sorted(closure.items()):
            j = jni_name(so)
            shutil.copy(path, os.path.join(out, j))
            libmap[so] = j
        for exe, j in EXECUTABLES.items():
            shutil.copy(os.path.join(usr, "bin", exe), os.path.join(out, j))
        libmap_all[abi] = libmap
        report[abi] = {"packages": versions, "libraries": len(closure)}
        print(f"[{abi}] {len(closure)} libraries + {len(EXECUTABLES)} executables")

    os.makedirs(ASSETS, exist_ok=True)
    # The soname->file map is identical for every ABI (same package set); verify and write.
    maps = list(libmap_all.values())
    merged = {}
    for m in maps:
        merged.update(m)
    with open(os.path.join(PROJECT, "tools/libmap.txt"), "w") as f:
        for so, j in sorted(merged.items()):
            if so != j:
                f.write(f"{so} {j}\n")
    share = os.path.join(ASSETS, "share")
    shutil.rmtree(share, ignore_errors=True)
    os.makedirs(share)
    src = os.path.join(usr, "share/qemu")
    for fw in FIRMWARE:
        if os.path.exists(os.path.join(src, fw)):
            shutil.copy(os.path.join(src, fw), share)
    shutil.copytree(os.path.join(src, "keymaps"), os.path.join(share, "keymaps"))
    with open(os.path.join(PROJECT, "app/src/main/assets/qemu/VERSIONS.json"), "w") as f:
        json.dump(report, f, indent=1, sort_keys=True)
    subprocess.run([sys.executable, os.path.join(PROJECT, "tools/fix_elf.py")], check=True)
    print("done")


if __name__ == "__main__":
    main()
