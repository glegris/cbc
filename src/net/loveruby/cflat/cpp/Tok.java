package net.loveruby.cflat.cpp;

/** One preprocessing token, per C99's translation phase 3 -- the unit
 *  everything else in this package (macro expansion, argument scanning,
 *  #if evaluation, stringification/pasting) works on, rather than raw
 *  characters. WS is a run of literal whitespace preserved so expanded
 *  output stays human-readable and column positions don't drift more
 *  than necessary; it never itself gets macro-expanded or matched by
 *  macro parameters. */
public class Tok {
    public enum Kind { IDENT, NUMBER, STRING, CHAR, PUNCT, WS, OTHER }

    public final Kind kind;
    public final String text;

    public Tok(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    public boolean isIdent() { return kind == Kind.IDENT; }
    public boolean isWs() { return kind == Kind.WS; }
    public boolean isPunct(String p) { return kind == Kind.PUNCT && text.equals(p); }

    public String toString() { return text; }
}
