package net.loveruby.cflat.ast;
import net.loveruby.cflat.parser.Token;
import net.loveruby.cflat.parser.ParserConstants;
import net.loveruby.cflat.utils.TextUtils;
import java.util.*;

public class Location {
    protected String sourceName;
    protected int lineno;
    protected CflatToken token;

    public Location(String sourceName, Token token) {
        this(sourceName, new CflatToken(token));
    }

    public Location(String sourceName, CflatToken token) {
        this(sourceName, token.lineno(), token);
    }

    /** Used when the reported (file, line) differs from where the token
     *  itself sits in the parser's flattened input -- e.g. a token that
     *  came from an #include'd file, or from after a #line directive
     *  (see net.loveruby.cflat.cpp.LineMap). Column and source-line text
     *  still come from the token itself: the flattened text's line
     *  content is identical to the original file's, only the line's
     *  overall position/identity differs. */
    public Location(String sourceName, int lineno, CflatToken token) {
        this.sourceName = sourceName;
        this.lineno = lineno;
        this.token = token;
    }

    public String sourceName() {
        return sourceName;
    }

    public CflatToken token() {
        return token;
    }

    /** line number */
    public int lineno() {
        return lineno;
    }

    public int column() {
        return token.column();
    }

    public String line() {
        return token.includedLine();
    }

    public String numberedLine() {
        return "line " + lineno + ": " + line();
    }

    public String toString() {
        return sourceName + ":" + lineno;
    }
}
