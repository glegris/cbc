#!/bin/bash
# Installs a build already produced by bin/build.sh (build/classes) as a
# standalone "<prefix>/bin/cbc" command, plus the x86 backend's runtime
# support library (lib/libcbc.a, built via "cd lib && make") and the C
# headers (import/). Unlike the repo's own bin/cbc (which locates its
# classes relative to itself at every invocation), the installed wrapper
# bakes in the absolute prefix path, so it works from any directory.

prefix="${1:-/usr/local/cbc}"

main()
{
    if ! [[ -d build/classes && -f lib/asm-9.7.1.jar && -f lib/libcbc.a ]]
    then
        echo "build/classes, lib/asm-9.7.1.jar and lib/libcbc.a must all" \
             "exist first -- run bin/build.sh, then (cd lib && make)" 1>&2
        exit 1
    fi
    echo "prefix=$prefix"
    invoke mkdir -p "$prefix/classes"
    invoke cp -r build/classes/. "$prefix/classes"
    invoke mkdir -p "$prefix/lib"
    invoke cp lib/asm-9.7.1.jar lib/libcbc.a "$prefix/lib"
    invoke mkdir -p "$prefix/bin"
    write_wrapper "$prefix/bin/cbc" "$prefix"
    invoke chmod +x "$prefix/bin/cbc"
    invoke rm -rf "$prefix/import"
    invoke cp -r import "$prefix/import"
    echo "cbc successfully installed as $prefix/bin/cbc"
}

write_wrapper()
{
    local dest="$1" prefix="$2"
    echo "writing $dest"
    cat > "$dest" <<WRAPPER
#!/bin/bash
exec java -cp "$prefix/classes:$prefix/lib/asm-9.7.1.jar" net.loveruby.cflat.compiler.Compiler "\$@"
WRAPPER
}

invoke()
{
    echo "$@"
    if ! "$@"
    then
        echo "install failed." 1>&2
        exit 1
    fi
}

main "$@"
