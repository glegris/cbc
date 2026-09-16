package net.loveruby.cflat.cpp;

import java.util.List;

/** Evaluates a #if/#elif constant expression (C99 6.10.1), after
 *  "defined" has already been resolved and macros expanded by the
 *  caller (see Preprocessor#evalCondition). Any identifier still left
 *  at this point (an undefined macro, or a keyword like "sizeof" this
 *  preprocessor doesn't understand) evaluates to 0, same as a real
 *  preprocessor does for a name that isn't a macro. Everything is
 *  evaluated as a signed 64-bit value; the signed/unsigned integer
 *  distinction #if is technically supposed to make doesn't matter for
 *  what this is actually used for (feature-flag-style conditionals). */
public class PPExprEval {
    private final List<Tok> toks;
    private int pos;

    public PPExprEval(List<Tok> toks) {
        this.toks = toks;
        this.pos = 0;
    }

    public static long eval(List<Tok> toks) {
        PPExprEval e = new PPExprEval(toks);
        long v = e.parseExpr();
        return v;
    }

    private Tok peek() {
        return pos < toks.size() ? toks.get(pos) : null;
    }

    private Tok next() {
        return pos < toks.size() ? toks.get(pos++) : null;
    }

    private boolean atPunct(String p) {
        Tok t = peek();
        return t != null && t.isPunct(p);
    }

    // expr -> conditional (',' conditional)*  -- comma operator: keep the last value
    private long parseExpr() {
        long v = parseConditional();
        while (atPunct(",")) {
            next();
            v = parseConditional();
        }
        return v;
    }

    private long parseConditional() {
        long cond = parseLogicalOr();
        if (atPunct("?")) {
            next();
            long thenV = parseExpr();
            expect(":");
            long elseV = parseConditional();
            return (cond != 0) ? thenV : elseV;
        }
        return cond;
    }

    private long parseLogicalOr() {
        long v = parseLogicalAnd();
        while (atPunct("|") && peekIs2("||")) {
            next(); next();
            long r = parseLogicalAnd();
            v = ((v != 0) || (r != 0)) ? 1 : 0;
        }
        return v;
    }

    private long parseLogicalAnd() {
        long v = parseBitOr();
        while (atPunct("&") && peekIs2("&&")) {
            next(); next();
            long r = parseBitOr();
            v = ((v != 0) && (r != 0)) ? 1 : 0;
        }
        return v;
    }

    private boolean peekIs2(String twoChar) {
        // current token is the first char of twoChar; check the next token matches the second char
        if (pos + 1 >= toks.size()) return false;
        Tok t2 = toks.get(pos + 1);
        return t2.isPunct(String.valueOf(twoChar.charAt(1)));
    }

    private long parseBitOr() {
        long v = parseBitXor();
        while (atPunct("|") && !peekIs2("||")) {
            next();
            v = v | parseBitXor();
        }
        return v;
    }

    private long parseBitXor() {
        long v = parseBitAnd();
        while (atPunct("^")) {
            next();
            v = v ^ parseBitAnd();
        }
        return v;
    }

    private long parseBitAnd() {
        long v = parseEquality();
        while (atPunct("&") && !peekIs2("&&")) {
            next();
            v = v & parseEquality();
        }
        return v;
    }

    private long parseEquality() {
        long v = parseRelational();
        for (;;) {
            if (atPunct("=") && peekIs2("==")) {
                next(); next();
                v = (v == parseRelational()) ? 1 : 0;
            }
            else if (atPunct("!") && peekIs2("!=")) {
                next(); next();
                v = (v != parseRelational()) ? 1 : 0;
            }
            else {
                return v;
            }
        }
    }

    private long parseRelational() {
        long v = parseShift();
        for (;;) {
            if (atPunct("<") && peekIs2("<=")) {
                next(); next();
                v = (v <= parseShift()) ? 1 : 0;
            }
            else if (atPunct(">") && peekIs2(">=")) {
                next(); next();
                v = (v >= parseShift()) ? 1 : 0;
            }
            else if (atPunct("<") && !peekIs2("<<")) {
                next();
                v = (v < parseShift()) ? 1 : 0;
            }
            else if (atPunct(">") && !peekIs2(">>")) {
                next();
                v = (v > parseShift()) ? 1 : 0;
            }
            else {
                return v;
            }
        }
    }

    private long parseShift() {
        long v = parseAdditive();
        for (;;) {
            if (atPunct("<") && peekIs2("<<")) {
                next(); next();
                v = v << parseAdditive();
            }
            else if (atPunct(">") && peekIs2(">>")) {
                next(); next();
                v = v >> parseAdditive();
            }
            else {
                return v;
            }
        }
    }

    private long parseAdditive() {
        long v = parseMultiplicative();
        for (;;) {
            if (atPunct("+")) { next(); v = v + parseMultiplicative(); }
            else if (atPunct("-")) { next(); v = v - parseMultiplicative(); }
            else return v;
        }
    }

    private long parseMultiplicative() {
        long v = parseUnary();
        for (;;) {
            if (atPunct("*")) { next(); v = v * parseUnary(); }
            else if (atPunct("/")) {
                next();
                long r = parseUnary();
                v = (r == 0) ? 0 : v / r;
            }
            else if (atPunct("%")) {
                next();
                long r = parseUnary();
                v = (r == 0) ? 0 : v % r;
            }
            else return v;
        }
    }

    private long parseUnary() {
        if (atPunct("!")) { next(); return (parseUnary() == 0) ? 1 : 0; }
        if (atPunct("~")) { next(); return ~parseUnary(); }
        if (atPunct("-")) { next(); return -parseUnary(); }
        if (atPunct("+")) { next(); return parseUnary(); }
        return parsePrimary();
    }

    private long parsePrimary() {
        Tok t = peek();
        if (t == null) {
            return 0;
        }
        if (t.isPunct("(")) {
            next();
            long v = parseExpr();
            expect(")");
            return v;
        }
        if (t.kind == Tok.Kind.NUMBER) {
            next();
            return parseNumber(t.text);
        }
        if (t.kind == Tok.Kind.CHAR) {
            next();
            return charLiteralValue(t.text);
        }
        if (t.isIdent()) {
            // Any identifier surviving to here isn't a macro (those were
            // already expanded), so it's 0, same as real cpp.
            next();
            return 0;
        }
        // Unexpected punctuation etc: skip it and treat as 0, rather
        // than throwing the whole compile away over a malformed #if.
        next();
        return 0;
    }

    private void expect(String p) {
        if (atPunct(p)) {
            next();
        }
        // A missing ')'/':' in a malformed #if is reported by the
        // caller noticing this evaluator didn't consume everything;
        // don't throw from deep in expression parsing.
    }

    private long parseNumber(String text) {
        String s = text;
        // Strip trailing integer suffixes (U/L in any case/order); a
        // float-looking constant (has '.', 'e'/'E' exponent not part of
        // a hex literal) isn't valid in a real #if either, so just take
        // its integer prefix as a best effort.
        int end = s.length();
        while (end > 0 && "uUlL".indexOf(s.charAt(end - 1)) >= 0) end--;
        s = s.substring(0, end);
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            if (s.startsWith("0") && s.length() > 1 && isOctal(s)) {
                return Long.parseLong(s.substring(1), 8);
            }
            int dot = s.indexOf('.');
            if (dot >= 0) {
                return (long) Double.parseDouble(text.replaceAll("[uUlL]+$", ""));
            }
            return Long.parseLong(s);
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean isOctal(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '7') return false;
        }
        return true;
    }

    private long charLiteralValue(String text) {
        // text includes the surrounding quotes, e.g. "'A'" or "'\\n'".
        String body = text.substring(1, text.length() - 1);
        if (body.isEmpty()) return 0;
        if (body.charAt(0) != '\\') return body.charAt(0);
        char esc = body.length() > 1 ? body.charAt(1) : '\\';
        switch (esc) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case '0': return 0;
            case '\\': return '\\';
            case '\'': return '\'';
            case '"': return '"';
            default: return esc;
        }
    }
}
