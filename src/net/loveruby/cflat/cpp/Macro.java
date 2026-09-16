package net.loveruby.cflat.cpp;

import java.util.List;

/** A #define'd macro. Object-like ("#define NAME value") has
 *  functionLike=false and an empty params list; function-like
 *  ("#define NAME(a,b) body") records its parameter names so
 *  MacroExpander can substitute them in body. Variadic macros
 *  ("...", "__VA_ARGS__") are not supported. */
public class Macro {
    public final String name;
    public final boolean functionLike;
    public final List<String> params;
    public final List<Tok> body;

    public Macro(String name, boolean functionLike, List<String> params, List<Tok> body) {
        this.name = name;
        this.functionLike = functionLike;
        this.params = params;
        this.body = body;
    }

    public int paramIndex(String name) {
        return params.indexOf(name);
    }
}
