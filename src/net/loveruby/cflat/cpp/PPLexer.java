package net.loveruby.cflat.cpp;

import java.util.ArrayList;
import java.util.List;

/** Tokenizes one logical source line (i.e. after backslash-newline
 *  splicing -- see Preprocessor#readLogicalLine) into preprocessing
 *  tokens. A block comment ("/&#42; ... &#42;/") can span several logical
 *  lines, so an instance of this class is kept around for a whole file
 *  and carries that one bit of state (inBlockComment) from one call to
 *  the next; a line comment ("//") never does, so it's handled entirely
 *  within a single call. */
public class PPLexer {
    private boolean inBlockComment = false;

    public boolean inBlockComment() {
        return inBlockComment;
    }

    public List<Tok> tokenize(String line) {
        List<Tok> toks = new ArrayList<Tok>();
        int i = 0;
        int n = line.length();
        if (inBlockComment) {
            int end = line.indexOf("*/");
            if (end < 0) {
                // Whole line is still inside the comment.
                toks.add(new Tok(Tok.Kind.WS, " "));
                return toks;
            }
            i = end + 2;
            inBlockComment = false;
            toks.add(new Tok(Tok.Kind.WS, " "));
        }
        while (i < n) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t' || c == '\f' || c == '\r') {
                int start = i;
                while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t'
                        || line.charAt(i) == '\f' || line.charAt(i) == '\r')) {
                    i++;
                }
                toks.add(new Tok(Tok.Kind.WS, line.substring(start, i)));
            }
            else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                // Line comment: the rest of the line disappears.
                break;
            }
            else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) {
                    inBlockComment = true;
                    i = n;
                }
                else {
                    i = end + 2;
                }
                toks.add(new Tok(Tok.Kind.WS, " "));
            }
            else if (c == '"') {
                int start = i;
                i++;
                while (i < n && line.charAt(i) != '"') {
                    if (line.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;  // closing quote
                toks.add(new Tok(Tok.Kind.STRING, line.substring(start, i)));
            }
            else if (c == '\'') {
                int start = i;
                i++;
                while (i < n && line.charAt(i) != '\'') {
                    if (line.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;  // closing quote
                toks.add(new Tok(Tok.Kind.CHAR, line.substring(start, i)));
            }
            else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                        i++;
                    }
                    else if ((d == '+' || d == '-') && i > start
                            && "eEpP".indexOf(line.charAt(i - 1)) >= 0) {
                        i++;  // exponent sign, e.g. the "+" in "1e+10"
                    }
                    else {
                        break;
                    }
                }
                toks.add(new Tok(Tok.Kind.NUMBER, line.substring(start, i)));
            }
            else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    i++;
                }
                toks.add(new Tok(Tok.Kind.IDENT, line.substring(start, i)));
            }
            else if (c == '#' && i + 1 < n && line.charAt(i + 1) == '#') {
                toks.add(new Tok(Tok.Kind.PUNCT, "##"));
                i += 2;
            }
            else if (isPunctChar(c)) {
                toks.add(new Tok(Tok.Kind.PUNCT, String.valueOf(c)));
                i++;
            }
            else {
                toks.add(new Tok(Tok.Kind.OTHER, String.valueOf(c)));
                i++;
            }
        }
        return toks;
    }

    private boolean isPunctChar(char c) {
        return "#(){}[],;:?.+-*/%&|^~!<>=".indexOf(c) >= 0;
    }
}
