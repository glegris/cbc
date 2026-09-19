package net.loveruby.cflat.cpp;

import java.util.List;

/** A #define'd macro. Object-like ("#define NAME value") has
 *  functionLike=false and an empty params list; function-like
 *  ("#define NAME(a,b) body") records its parameter names so
 *  MacroExpander can substitute them in body. A variadic function-like
 *  macro ("#define NAME(a, ...) body") has variadic=true; its "..."
 *  isn't itself a named parameter, but is referred to in body as
 *  "__VA_ARGS__" -- see paramIndex(), which treats that name as one
 *  more parameter slot right after the declared ones (only the GNU/C99
 *  bare "__VA_ARGS__" form is supported, not the named-variadic
 *  extension, e.g. "args...", nor C23's "__VA_OPT__"). */
public class Macro {
    public final String name;
    public final boolean functionLike;
    public final boolean variadic;
    public final List<String> params;
    public final List<Tok> body;

    public Macro(String name, boolean functionLike, boolean variadic,
            List<String> params, List<Tok> body) {
        this.name = name;
        this.functionLike = functionLike;
        this.variadic = variadic;
        this.params = params;
        this.body = body;
    }

    public int paramIndex(String name) {
        if (variadic && name.equals("__VA_ARGS__")) {
            return params.size();
        }
        return params.indexOf(name);
    }
}
