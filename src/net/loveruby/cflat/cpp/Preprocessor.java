package net.loveruby.cflat.cpp;

import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.exception.FileException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A real (if scoped-down) C preprocessor, run as a text-to-text pass
 *  over a source file before it ever reaches the parser: object-like and
 *  function-like macros (#define/#undef, with # stringification and ##
 *  token pasting), conditional compilation (#ifdef/#ifndef/#if/#elif/
 *  #else/#endif, with a real constant-expression evaluator -- see
 *  PPExprEval), #include "..."/&lt;...&gt; (searched on the same -I path
 *  as "import"), backslash-newline line splicing, and #error/#pragma.
 *
 * Not supported, scoped out for size: variadic macros (... and
 * __VA_ARGS__), predefined macros (__LINE__, __FILE__, __DATE__, ...),
 * #line, and multi-line function-like macro invocations (a call's whole
 * argument list must be on one logical line, after splicing).
 *
 * Every input line becomes exactly one output line (a directive line, or
 * a suppressed line inside an inactive #if branch, becomes a blank
 * line), so line numbers in the rest of the compiler's error messages
 * stay meaningful -- except across an #include, where the included
 * file's own lines are spliced in inline and everything in the
 * including file *after* the #include shifts by however many lines that
 * added; there is no #line-style mechanism to correct for it. */
public class Preprocessor {
    private final List<String> includePaths;
    private final ErrorHandler errorHandler;
    private final Map<String, Macro> macros = new HashMap<String, Macro>();
    private final MacroExpander expander;
    private final Set<String> includeStack = new LinkedHashSet<String>();

    private static final Pattern INCLUDE_QUOTED = Pattern.compile("include\\s*\"([^\"]+)\"");
    private static final Pattern INCLUDE_ANGLED = Pattern.compile("include\\s*<([^>]+)>");
    private static final Pattern DIRECTIVE_NAME = Pattern.compile("#\\s*([A-Za-z_]*)");

    public Preprocessor(List<String> includePaths, ErrorHandler errorHandler) {
        this.includePaths = includePaths;
        this.errorHandler = errorHandler;
        this.expander = new MacroExpander(macros, errorHandler);
    }

    public String preprocessFile(File file) throws FileException {
        StringBuilder out = new StringBuilder();
        processFile(file, out);
        return out.toString();
    }

    private static class CondFrame {
        boolean parentActive;
        boolean active;
        boolean everActive;
        boolean sawElse;
    }

    private void processFile(File file, StringBuilder out) throws FileException {
        String canon = canonicalPath(file);
        if (includeStack.contains(canon)) {
            errorHandler.error("recursive #include: " + file.getPath());
            return;
        }
        includeStack.add(canon);
        try {
            List<String> lines = readAllLines(file);
            PPLexer lexer = new PPLexer();
            Deque<CondFrame> condStack = new ArrayDeque<CondFrame>();
            int i = 0;
            int lineNo = 0;
            while (i < lines.size()) {
                boolean wasInComment = lexer.inBlockComment();
                StringBuilder logical = new StringBuilder();
                int physicalCount = 0;
                while (i < lines.size()) {
                    String raw = lines.get(i);
                    i++;
                    physicalCount++;
                    if (!wasInComment && endsWithBackslash(raw)) {
                        logical.append(stripTrailingBackslash(raw));
                        logical.append(' ');
                    }
                    else {
                        logical.append(raw);
                        break;
                    }
                }
                lineNo += physicalCount;
                String line = logical.toString();
                boolean active = isActive(condStack);

                if (!wasInComment && isDirectiveLine(line)) {
                    handleDirective(line, file, lineNo, condStack, out, active);
                }
                else if (active) {
                    List<Tok> toks = lexer.tokenize(line);
                    expander.setLocation(file.getPath(), lineNo);
                    List<Tok> expanded = expander.expand(toks);
                    out.append(render(expanded));
                }
                else {
                    // Still need to run the lexer (inactive text may
                    // contain an unterminated /* that should suppress
                    // directive-detection on later lines, and a stray
                    // #if-family line inside an inactive block must
                    // still nest correctly -- handleDirective deals with
                    // that; a non-directive inactive line just needs its
                    // comment-state effects, not its content).
                    lexer.tokenize(line);
                }
                out.append('\n');
                for (int k = 1; k < physicalCount; k++) out.append('\n');
            }
            if (!condStack.isEmpty()) {
                errorHandler.error(file.getPath() + ": unterminated #if/#ifdef/#ifndef");
            }
        }
        finally {
            includeStack.remove(canon);
        }
    }

    private boolean isActive(Deque<CondFrame> condStack) {
        return condStack.isEmpty() || condStack.peek().active;
    }

    private boolean isDirectiveLine(String line) {
        String t = line.trim();
        return t.startsWith("#");
    }

    private boolean endsWithBackslash(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) end--;
        return end > 0 && line.charAt(end - 1) == '\\';
    }

    private String stripTrailingBackslash(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) end--;
        return line.substring(0, end - 1);
    }

    private void handleDirective(String line, File file, int lineNo,
            Deque<CondFrame> condStack, StringBuilder out, boolean active) {
        Matcher m = DIRECTIVE_NAME.matcher(line.trim());
        String name = m.lookingAt() ? m.group(1) : "";
        String rest = line.trim().substring(m.end());

        if (name.equals("ifdef") || name.equals("ifndef")) {
            CondFrame f = new CondFrame();
            f.parentActive = isActive(condStack);
            boolean cond = false;
            if (f.parentActive) {
                String macroName = rest.trim();
                cond = macros.containsKey(macroName);
                if (name.equals("ifndef")) cond = !cond;
            }
            f.active = f.parentActive && cond;
            f.everActive = f.active;
            condStack.push(f);
        }
        else if (name.equals("if")) {
            CondFrame f = new CondFrame();
            f.parentActive = isActive(condStack);
            f.active = f.parentActive && (evalCondition(rest, file, lineNo) != 0);
            f.everActive = f.active;
            condStack.push(f);
        }
        else if (name.equals("elif")) {
            if (condStack.isEmpty()) {
                errorHandler.error(file.getPath() + ":" + lineNo + ": #elif without #if");
                return;
            }
            CondFrame f = condStack.peek();
            if (f.sawElse) {
                errorHandler.error(file.getPath() + ":" + lineNo + ": #elif after #else");
            }
            if (!f.parentActive || f.everActive) {
                f.active = false;
            }
            else {
                f.active = (evalCondition(rest, file, lineNo) != 0);
                f.everActive = f.active;
            }
        }
        else if (name.equals("else")) {
            if (condStack.isEmpty()) {
                errorHandler.error(file.getPath() + ":" + lineNo + ": #else without #if");
                return;
            }
            CondFrame f = condStack.peek();
            if (f.sawElse) {
                errorHandler.error(file.getPath() + ":" + lineNo + ": duplicate #else");
            }
            f.sawElse = true;
            if (!f.parentActive || f.everActive) {
                f.active = false;
            }
            else {
                f.active = true;
                f.everActive = true;
            }
        }
        else if (name.equals("endif")) {
            if (condStack.isEmpty()) {
                errorHandler.error(file.getPath() + ":" + lineNo + ": #endif without #if");
                return;
            }
            condStack.pop();
        }
        else if (!active) {
            // Any other directive is only meaningful when active; an
            // inactive #define/#include/#error/etc. must be skipped
            // entirely (that's the whole point of #if).
        }
        else if (name.equals("define")) {
            handleDefine(rest, file, lineNo);
        }
        else if (name.equals("undef")) {
            macros.remove(rest.trim());
        }
        else if (name.equals("include")) {
            handleInclude(rest, file, lineNo, out);
        }
        else if (name.equals("error")) {
            errorHandler.error(file.getPath() + ":" + lineNo + ": #error " + rest.trim());
        }
        else if (name.equals("pragma") || name.equals("")) {
            // #pragma: silently ignored (no pragmas this compiler acts
            // on). A bare "#" with nothing after it is also a legal
            // (no-op) null directive.
        }
        else if (name.equals("line")) {
            // Not supported (see class doc); silently ignored rather
            // than treated as an error, since plenty of real headers
            // emit these unconditionally.
        }
        else {
            errorHandler.error(file.getPath() + ":" + lineNo + ": unknown preprocessor directive: #" + name);
        }
    }

    private long evalCondition(String rest, File file, int lineNo) {
        List<Tok> toks = new PPLexer().tokenize(rest);
        List<Tok> withDefined = resolveDefined(toks);
        expander.setLocation(file.getPath(), lineNo);
        List<Tok> expanded = expander.expand(withDefined);
        List<Tok> noWs = new ArrayList<Tok>();
        for (Tok t : expanded) {
            if (!t.isWs()) noWs.add(t);
        }
        if (noWs.isEmpty()) {
            errorHandler.error(file.getPath() + ":" + lineNo + ": #if with no expression");
            return 0;
        }
        return PPExprEval.eval(noWs);
    }

    /** Replaces every "defined(NAME)" / "defined NAME" with a 1/0
     *  literal, based on whether NAME is *currently* a macro -- this has
     *  to happen before general macro expansion, since "defined"'s
     *  operand must never itself be macro-expanded (C99 6.10.1p1). */
    private List<Tok> resolveDefined(List<Tok> toks) {
        List<Tok> out = new ArrayList<Tok>();
        int i = 0;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (t.isIdent() && t.text.equals("defined")) {
                int j = i + 1;
                while (j < toks.size() && toks.get(j).isWs()) j++;
                boolean paren = j < toks.size() && toks.get(j).isPunct("(");
                if (paren) {
                    j++;
                    while (j < toks.size() && toks.get(j).isWs()) j++;
                }
                String macroName = null;
                if (j < toks.size() && toks.get(j).isIdent()) {
                    macroName = toks.get(j).text;
                    j++;
                }
                if (paren) {
                    while (j < toks.size() && toks.get(j).isWs()) j++;
                    if (j < toks.size() && toks.get(j).isPunct(")")) j++;
                }
                boolean isDef = macroName != null && macros.containsKey(macroName);
                out.add(new Tok(Tok.Kind.NUMBER, isDef ? "1" : "0"));
                i = j;
            }
            else {
                out.add(t);
                i++;
            }
        }
        return out;
    }

    private void handleDefine(String rest, File file, int lineNo) {
        List<Tok> toks = new PPLexer().tokenize(rest);
        int i = 0;
        while (i < toks.size() && toks.get(i).isWs()) i++;
        if (i >= toks.size() || !toks.get(i).isIdent()) {
            errorHandler.error(file.getPath() + ":" + lineNo + ": macro name missing in #define");
            return;
        }
        String name = toks.get(i).text;
        i++;
        boolean functionLike = false;
        List<String> params = new ArrayList<String>();
        if (i < toks.size() && toks.get(i).isPunct("(")) {
            functionLike = true;
            i++;
            boolean variadic = false;
            while (i < toks.size() && !toks.get(i).isPunct(")")) {
                Tok t = toks.get(i);
                if (t.isWs() || t.isPunct(",")) {
                    i++;
                }
                else if (t.isPunct(".")) {
                    variadic = true;
                    i++;
                }
                else if (t.isIdent()) {
                    params.add(t.text);
                    i++;
                }
                else {
                    i++;
                }
            }
            if (i < toks.size() && toks.get(i).isPunct(")")) i++;
            if (variadic) {
                errorHandler.error(file.getPath() + ":" + lineNo
                        + ": variadic macros are not supported: " + name);
                return;
            }
        }
        while (i < toks.size() && toks.get(i).isWs()) i++;
        List<Tok> body = new ArrayList<Tok>(toks.subList(i, toks.size()));
        while (!body.isEmpty() && body.get(body.size() - 1).isWs()) {
            body.remove(body.size() - 1);
        }
        macros.put(name, new Macro(name, functionLike, params, body));
    }

    private void handleInclude(String rest, File file, int lineNo, StringBuilder out) {
        Matcher qm = INCLUDE_QUOTED.matcher("include" + rest);
        Matcher am = INCLUDE_ANGLED.matcher("include" + rest);
        String includeName;
        boolean quoted;
        if (qm.lookingAt()) {
            includeName = qm.group(1);
            quoted = true;
        }
        else if (am.lookingAt()) {
            includeName = am.group(1);
            quoted = false;
        }
        else {
            errorHandler.error(file.getPath() + ":" + lineNo
                    + ": malformed #include: " + rest.trim());
            return;
        }
        File included = resolveInclude(includeName, quoted, file);
        if (included == null) {
            errorHandler.error(file.getPath() + ":" + lineNo
                    + ": file not found: " + includeName);
            return;
        }
        try {
            processFile(included, out);
        }
        catch (FileException ex) {
            errorHandler.error(file.getPath() + ":" + lineNo + ": " + ex.getMessage());
        }
    }

    private File resolveInclude(String name, boolean quoted, File includingFile) {
        if (quoted) {
            File sibling = new File(includingFile.getParentFile(), name);
            if (sibling.exists()) return sibling;
        }
        for (String dir : includePaths) {
            File f = new File(dir, name);
            if (f.exists()) return f;
        }
        File direct = new File(name);
        return direct.exists() ? direct : null;
    }

    private String render(List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (Tok t : toks) {
            sb.append(t.text);
        }
        return sb.toString();
    }

    private List<String> readAllLines(File file) throws FileException {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new FileException(file.getPath() + ": " + ex.getMessage());
        }
    }

    private String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        }
        catch (IOException ex) {
            return file.getAbsolutePath();
        }
    }
}
