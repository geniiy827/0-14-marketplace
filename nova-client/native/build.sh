#!/bin/sh
# libnova.so: no NDK here, so link against stub libc/libdl/liblog with the right sonames
set -e
cd /home/claude/work/native
T="--target=armv7a-linux-androideabi21 -mthumb -mfloat-abi=softfp -mfpu=vfpv3-d16 -nostdlib -fPIC -shared -fuse-ld=lld"
for x in c dl log m; do clang-18 $T -w -Wl,-soname,lib$x.so -o stubs/lib$x.so stubs/$x.c; done
clang-18 $T -O2 -fno-stack-protector -ffreestanding -Wl,--hash-style=both -Wl,-z,max-page-size=4096 \
  -Wl,-soname,libnova.so -o libnova.so nova.c -Lstubs -lc -lm -ldl -llog
