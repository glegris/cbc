#!/bin/bash
# One command to build cbc and run its test suites:
#   1. builds via bin/build.sh
#   2. runs the JVM backend regression suite (test/run_jvm.sh) -- this
#      works anywhere a JDK is installed, and is the only suite that can
#      run at all in a sandbox without a 32-bit x86 toolchain.
#   3. runs the native x86 suite (test/test_cbc.sh via test/run.sh), but
#      only if this machine actually has what GNULinker.java needs to
#      link a 32-bit executable (crt*.o, the 32-bit dynamic linker); many
#      modern/minimal Linux setups don't have the multilib packages for
#      that installed, and that's not something this script can fix, so
#      it's reported as skipped rather than failed.
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "=== build ==="
if ! ./bin/build.sh; then
    echo "test.sh: build failed" 1>&2
    exit 1
fi

overall_status=0

echo
echo "=== JVM backend suite (test/run_jvm.sh) ==="
if ! ./test/run_jvm.sh; then
    overall_status=1
fi

echo
echo "=== native x86 suite (test/test_cbc.sh) ==="
x86_runtime_files=(
    /usr/lib32/crt1.o
    /usr/lib32/crti.o
    /usr/lib32/Scrt1.o
    /usr/lib32/crtn.o
    /lib/ld-linux.so.2
)
missing=()
for f in "${x86_runtime_files[@]}"; do
    [ -f "$f" ] || missing+=("$f")
done

if [ ${#missing[@]} -ne 0 ]; then
    echo "SKIPPED: this machine is missing the 32-bit runtime objects"
    echo "GNULinker.java needs to link a native x86 executable:"
    for f in "${missing[@]}"; do
        echo "  - $f"
    done
    echo "(install the 32-bit/multilib glibc-devel package for your distro" \
         "to enable this suite)"
else
    ( cd test && CBC="$DIR/bin/cbc" ./run.sh )
    if [ $? -ne 0 ]; then
        overall_status=1
    fi
fi

echo
if [ $overall_status -eq 0 ]; then
    echo "test.sh: OK"
else
    echo "test.sh: FAILED"
fi
exit $overall_status
