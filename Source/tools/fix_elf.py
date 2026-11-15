#!/usr/bin/env python3
"""
Post-processes the bundled QEMU ELF files in app/src/main/jniLibs/<abi>/ so that they run
straight from Android's nativeLibraryDir without any helper files in the app's data directory:

  * every DT_NEEDED entry that refers to a versioned soname (libz.so.1, libglib-2.0.so.0, ...)
    is rewritten to the real file name inside the APK (libdvm_z_so_1.so, ...), using
    tools/libmap.txt;
  * DT_SONAME of renamed libraries is set to their file name;
  * the Termux RUNPATH (/data/data/com.termux/files/usr/lib) is removed.

Afterwards no symlink farm and no LD_LIBRARY_PATH into filesDir are needed. Requires patchelf.
Idempotent: running it twice changes nothing.
"""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNI = os.path.join(ROOT, "app/src/main/jniLibs")
LIBMAP = os.path.join(ROOT, "tools/libmap.txt")

def sh(*a):
    return subprocess.run(a, check=True, capture_output=True, text=True).stdout

def main():
    m = {}
    for line in open(LIBMAP):
        p = line.split()
        if len(p) == 2: m[p[0]] = p[1]
    files_rev = {v: k for k, v in m.items()}
    for abi in sorted(os.listdir(JNI)):
        d = os.path.join(JNI, abi)
        names = set(os.listdir(d))
        for f in sorted(names):
            if not f.endswith(".so"): continue
            path = os.path.join(d, f)
            needed = [l.strip() for l in sh("patchelf", "--print-needed", path).splitlines() if l.strip()]
            args = []
            for n in needed:
                if n in m:
                    args += ["--replace-needed", n, m[n]]
            if f in files_rev:
                cur = sh("patchelf", "--print-soname", path).strip()
                if cur != f: args += ["--set-soname", f]
            rp = sh("patchelf", "--print-rpath", path).strip()
            if rp: args += ["--remove-rpath"]
            if args:
                subprocess.run(["patchelf", *args, path], check=True)
            # verify every dependency now resolves to a file in the APK or to an Android system library
            for n in [l.strip() for l in sh("patchelf", "--print-needed", path).splitlines() if l.strip()]:
                if n not in names and n not in SYSTEM:
                    sys.exit(f"{abi}/{f}: unresolved dependency {n}")
        print(f"{abi}: ok ({len(names)} files)")

SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so", "libEGL.so",
          "libGLESv2.so", "libGLESv3.so", "libOpenSLES.so", "libaaudio.so", "libmediandk.so", "libvulkan.so"}

if __name__ == "__main__":
    main()
