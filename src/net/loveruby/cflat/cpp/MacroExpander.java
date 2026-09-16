package net.loveruby.cflat.cpp;

import net.loveruby.cflat.utils.ErrorHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Expands macro invocations in a token list, per (an approximation of)
 *  C99 6.10.3. Recursion through a macro's own name is prevented with a
 *  "currently expanding" name set threaded through every recursive call
 *  (the standard's "painted blue" rule, simplified: this backend doesn't
 *  track hide sets per-token, just one shared set per top-level
 *  expansion, which is enough to stop infinite loops on self-referential
 *  macros without being fully spec-accurate on which specific token
 *  re-expands in more exotic mutual-recursion cases). */
public class MacroExpander {
    private final Map<String, Macro> macros;
    private final ErrorHandler errorHandler;
    private String currentFile = "?";
    private int currentLine = 0;

    public MacroExpander(Map<String, Macro> macros, ErrorHandler errorHandler) {
        this.macros = macros;
        this.errorHandler = errorHandler;
    }

    public void setLocation(String file, int line) {
        this.currentFile = file;
        this.currentLine = line;
    }

    public List<Tok> expand(List<Tok> input) {
        return expand(input, new HashSet<String>());
    }

    private List<Tok> expand(List<Tok> input, Set<String> hideSet) {
        List<Tok> out = new ArrayList<Tok>();
        int i = 0;
        while (i < input.size()) {
            Tok t = input.get(i);
            if (!t.isIdent() || !macros.containsKey(t.text) || hideSet.contains(t.text)) {
                out.add(t);
                i++;
                continue;
            }
            Macro m = macros.get(t.text);
            if (!m.functionLike) {
                Set<String> newHide = new HashSet<String>(hideSet);
                newHide.add(m.name);
                out.addAll(expand(m.body, newHide));
                i++;
                continue;
            }
            // Function-like: only invoked when directly followed by "(".
            int j = i + 1;
            while (j < input.size() && input.get(j).isWs()) j++;
            if (j >= input.size() || !input.get(j).isPunct("(")) {
                out.add(t);
                i++;
                continue;
            }
            ArgScan scan = scanArgs(input, j);
            if (scan == null) {
                error("unterminated argument list for macro " + m.name + "()");
                out.add(t);
                i++;
                continue;
            }
            List<List<Tok>> args = splitArgs(scan.tokens, m);
            List<Tok> substituted = substitute(m, args, hideSet);
            Set<String> newHide = new HashSet<String>(hideSet);
            newHide.add(m.name);
            out.addAll(expand(substituted, newHide));
            i = scan.nextIndex;
        }
        return out;
    }

    private static class ArgScan {
        List<Tok> tokens;   // everything between the outer ( and ), inclusive commas
        int nextIndex;      // index right after the closing )
    }

    /** input.get(openParenIndex) must be "(". Scans to the matching ")",
     *  respecting nested parens (commas/parens inside a nested macro
     *  call's own args don't end the outer argument list early). */
    private ArgScan scanArgs(List<Tok> input, int openParenIndex) {
        List<Tok> collected = new ArrayList<Tok>();
        int depth = 0;
        for (int i = openParenIndex; i < input.size(); i++) {
            Tok t = input.get(i);
            if (t.isPunct("(")) {
                depth++;
                if (depth == 1) continue;  // the outer "(" itself isn't part of the args
            }
            else if (t.isPunct(")")) {
                depth--;
                if (depth == 0) {
                    ArgScan s = new ArgScan();
                    s.tokens = collected;
                    s.nextIndex = i + 1;
                    return s;
                }
            }
            collected.add(t);
        }
        return null;  // ran off the end without closing
    }

    private List<List<Tok>> splitArgs(List<Tok> inner, Macro m) {
        List<List<Tok>> args = new ArrayList<List<Tok>>();
        List<Tok> cur = new ArrayList<Tok>();
        int depth = 0;
        for (Tok t : inner) {
            if (t.isPunct("(")) depth++;
            else if (t.isPunct(")")) depth--;
            if (t.isPunct(",") && depth == 0) {
                args.add(trimWs(cur));
                cur = new ArrayList<Tok>();
            }
            else {
                cur.add(t);
            }
        }
        // F() with zero declared params is zero arguments, not one empty
        // one; anything else always closes out with one final argument
        // (possibly empty, e.g. the trailing "" in F(1,)).
        boolean noArgsAtAll = m.params.isEmpty() && args.isEmpty() && trimWs(cur).isEmpty();
        if (!noArgsAtAll) {
            args.add(trimWs(cur));
        }
        if (args.size() != m.params.size()) {
            error("macro " + m.name + " expects " + m.params.size()
                    + " argument(s), got " + args.size());
            while (args.size() < m.params.size()) args.add(new ArrayList<Tok>());
            while (args.size() > m.params.size()) args.remove(args.size() - 1);
        }
        return args;
    }

    private List<Tok> trimWs(List<Tok> toks) {
        int start = 0, end = toks.size();
        while (start < end && toks.get(start).isWs()) start++;
        while (end > start && toks.get(end - 1).isWs()) end--;
        return new ArrayList<Tok>(toks.subList(start, end));
    }

    /** Substitutes m's parameters in its body with the call's actual
     *  arguments (macro-expanded first, unless adjacent to "#"/"##",
     *  per C99 6.10.3.1), then resolves every "##" paste left in the
     *  result (6.10.3.3). Stringification (6.10.3.2) happens inline as
     *  the body is walked, since "#param" is only meaningful for a
     *  function-like macro's own parameter, never a general expression. */
    private List<Tok> substitute(Macro m, List<List<Tok>> args, Set<String> hideSet) {
        List<Tok> result = new ArrayList<Tok>();
        List<Tok> body = m.body;
        for (int i = 0; i < body.size(); i++) {
            Tok t = body.get(i);
            if (t.isPunct("#")) {
                int j = i + 1;
                while (j < body.size() && body.get(j).isWs()) j++;
                if (j < body.size() && body.get(j).isIdent()) {
                    int pidx = m.paramIndex(body.get(j).text);
                    if (pidx >= 0) {
                        result.add(new Tok(Tok.Kind.STRING, stringify(args.get(pidx))));
                        i = j;
                        continue;
                    }
                }
                result.add(t);
                continue;
            }
            if (t.isIdent()) {
                int pidx = m.paramIndex(t.text);
                if (pidx >= 0) {
                    boolean pastedLeft = isHashHash(lastNonWs(result));
                    boolean pastedRight = isHashHash(nextNonWs(body, i + 1));
                    List<Tok> argToks = args.get(pidx);
                    result.addAll((pastedLeft || pastedRight) ? argToks : expand(argToks, hideSet));
                    continue;
                }
            }
            result.add(t);
        }
        return pasteTokens(result);
    }

    private boolean isHashHash(Tok t) {
        return t != null && t.isPunct("##");
    }

    private Tok lastNonWs(List<Tok> toks) {
        for (int i = toks.size() - 1; i >= 0; i--) {
            if (!toks.get(i).isWs()) return toks.get(i);
        }
        return null;
    }

    private Tok nextNonWs(List<Tok> toks, int from) {
        for (int i = from; i < toks.size(); i++) {
            if (!toks.get(i).isWs()) return toks.get(i);
        }
        return null;
    }

    private String stringify(List<Tok> toks) {
        StringBuilder sb = new StringBuilder("\"");
        boolean started = false;
        boolean lastWasWs = false;
        for (Tok t : toks) {
            if (t.isWs()) {
                lastWasWs = started;
                continue;
            }
            if (lastWasWs) sb.append(' ');
            lastWasWs = false;
            started = true;
            if (t.kind == Tok.Kind.STRING || t.kind == Tok.Kind.CHAR) {
                for (char c : t.text.toCharArray()) {
                    if (c == '"' || c == '\\') sb.append('\\');
                    sb.append(c);
                }
            }
            else {
                sb.append(t.text);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private List<Tok> pasteTokens(List<Tok> in) {
        List<Tok> out = new ArrayList<Tok>();
        int i = 0;
        while (i < in.size()) {
            Tok t = in.get(i);
            if (t.isPunct("##")) {
                while (!out.isEmpty() && out.get(out.size() - 1).isWs()) {
                    out.remove(out.size() - 1);
                }
                Tok left = out.isEmpty() ? null : out.remove(out.size() - 1);
                int j = i + 1;
                while (j < in.size() && in.get(j).isWs()) j++;
                Tok right = (j < in.size()) ? in.get(j) : null;
                String pasted = (left == null ? "" : left.text) + (right == null ? "" : right.text);
                out.addAll(retokenize(pasted));
                i = (right != null) ? j + 1 : j;
            }
            else {
                out.add(t);
                i++;
            }
        }
        return out;
    }

    private List<Tok> retokenize(String text) {
        List<Tok> toks = new PPLexer().tokenize(text);
        List<Tok> result = new ArrayList<Tok>();
        for (Tok t : toks) {
            if (!t.isWs()) result.add(t);
        }
        if (result.isEmpty()) {
            error("'##' produced an invalid token: \"" + text + "\"");
        }
        return result;
    }

    private void error(String msg) {
        errorHandler.error(currentFile + ":" + currentLine + ": " + msg);
    }
}
