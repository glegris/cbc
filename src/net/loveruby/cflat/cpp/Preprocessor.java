package net.loveruby.cflat.cpp;

import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.exception.FileException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
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
 *  function-like macros (#define/#undef, with # stringification, ##
 *  token pasting, variadic macros -- "..."/__VA_ARGS__, including the
 *  GNU ", ##__VA_ARGS__" comma-elision extension -- and the predefined
 *  __LINE__/__FILE__/__DATE__/__TIME__/__STDC__/__STDC_VERSION__/
 *  __COUNTER__), conditional compilation (#ifdef/#ifndef/#if/#elif/
 *  #else/#endif, with a real constant-expression evaluator -- see
 *  PPExprEval), #include "..."/&lt;...&gt; (searched on the same -I path
 *  as "import"), backslash-newline line splicing, #line (see
 *  handleLine's own doc comment for the one thing it doesn't do),
 *  _Pragma(...), and #error/#pragma.
 *
 * Not supported, scoped out for size: the GNU named-variadic extension
 * ("args..." instead of a bare "...", referred to by that name instead
 * of __VA_ARGS__), C23's __VA_OPT__, any macro identifying this
 * compiler/platform/architecture (__GNUC__, __unix__, ... -- cbc isn't
 * gcc/clang, and pretending otherwise would make real headers take
 * branches this compiler doesn't actually support), and multi-line
 * function-like macro invocations (a call's whole argument list must be
 * on one logical line, after splicing).
 *
 * Every input line becomes exactly one output line (a directive line, or
 * a suppressed line inside an inactive #if branch, becomes a blank
 * line), so line numbers in the rest of the compiler's error messages
 * would otherwise stay meaningful only within a single file -- an
 * #include splices the included file's own lines in inline, and
 * everything in the including file *after* the #include shifts by
 * however many lines that added. This pass corrects for that itself, by
 * building a LineMap (see lineMap()) alongside the flattened text that
 * maps a line in that text back to the (file, line) it actually came
 * from -- consulted by Parser#location so error messages name the file
 * that really has the mistake, not just the top-level file being
 * compiled. #line (see handleLine) updates both that map and
 * __LINE__/__FILE__ together, so the two stay consistent. */
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
        definePredefinedMacros();
    }

    /** C99 6.10.8's predefined macros this compiler actually defines:
     *  __STDC__/__STDC_VERSION__ (fixed), __DATE__/__TIME__ (computed
     *  once here -- "now", at the moment compilation started, same as
     *  real C requires: one fixed value for the whole translation unit,
     *  not re-evaluated per use or per file), and placeholder entries
     *  for __LINE__/__FILE__/__COUNTER__ so "#ifdef"/"defined()" report
     *  them as defined -- their real values are never read from here:
     *  MacroExpander#expand special-cases those three names before ever
     *  consulting a stored body. */
    private void definePredefinedMacros() {
        defineLiteral("__STDC__", new Tok(Tok.Kind.NUMBER, "1"));
        defineLiteral("__STDC_VERSION__", new Tok(Tok.Kind.NUMBER, "199901L"));
        Calendar now = Calendar.getInstance();
        defineLiteral("__DATE__", new Tok(Tok.Kind.STRING, "\"" + formatDate(now) + "\""));
        defineLiteral("__TIME__", new Tok(Tok.Kind.STRING, "\"" + formatTime(now) + "\""));
        List<Tok> unused = new ArrayList<Tok>();
        List<String> noParams = new ArrayList<String>();
        macros.put("__LINE__", new Macro("__LINE__", false, false, noParams, unused));
        macros.put("__FILE__", new Macro("__FILE__", false, false, noParams, unused));
        macros.put("__COUNTER__", new Macro("__COUNTER__", false, false, noParams, unused));
    }

    private void defineLiteral(String name, Tok value) {
        List<Tok> body = new ArrayList<Tok>();
        body.add(value);
        macros.put(name, new Macro(name, false, false, new ArrayList<String>(), body));
    }

    private static final String[] MONTH_ABBREV = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    /** __DATE__'s exact C99 6.10.8.1 format: "Mmm dd yyyy", where a
     *  single-digit day is space-padded (not zero-padded, unlike
     *  everything else here) -- e.g. "Jan  1 2024". */
    private String formatDate(Calendar cal) {
        String month = MONTH_ABBREV[cal.get(Calendar.MONTH)];
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String dayStr = (day < 10 ? " " : "") + day;
        return month + " " + dayStr + " " + cal.get(Calendar.YEAR);
    }

    private String formatTime(Calendar cal) {
        return String.format("%02d:%02d:%02d", cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
    }

    private LineMap lineMap;
    private int outputLineNo;

    public String preprocessFile(File file) throws FileException {
        StringBuilder out = new StringBuilder();
        lineMap = new LineMap();
        outputLineNo = 0;
        processFile(file, out);
        return out.toString();
    }

    /** Maps a line number in the string preprocessFile() returned back to
     *  the (file, line) it actually came from -- see LineMap's own doc
     *  comment. Only meaningful after preprocessFile() has been called. */
    public LineMap lineMap() {
        return lineMap;
    }

    private static class CondFrame {
        boolean parentActive;
        boolean active;
        boolean everActive;
        boolean sawElse;
    }

    /** __LINE__/__FILE__'s current reporting state within one
     *  processFile() call: reportedLine = physicalLine + lineDelta.
     *  Adjusted by #line (see handleLine); an included file gets its
     *  own fresh instance (processFile is recursive, one call per
     *  nesting level), so #line is correctly scoped to the file that
     *  used it, exactly like a real preprocessor's. */
    private static class LineState {
        String reportedFile;
        long lineDelta;
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
            LineState lineState = new LineState();
            lineState.reportedFile = file.getPath();
            lineState.lineDelta = 0;
            lineMap.addBreakpoint(outputLineNo + 1, lineState.reportedFile,
                    (int) (1 + lineState.lineDelta));
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
                    // A directive line's own output (a blank placeholder,
                    // appended below) lands *after* whatever it
                    // recursively splices in here (an #include's
                    // content) -- so a breakpoint recorded inside
                    // handleDirective/handleInclude/handleLine has to
                    // skip over this line's still-unappended
                    // physicalCount lines to land on the right spot;
                    // hence passing physicalCount through.
                    handleDirective(line, file, lineNo, condStack, out, active, lineState, physicalCount);
                }
                else if (active) {
                    List<Tok> toks = lexer.tokenize(line);
                    expander.setLocation(lineState.reportedFile, (int) (lineNo + lineState.lineDelta));
                    List<Tok> expanded = expander.expand(toks);
                    expanded = stripPragmaOperator(expanded, file, lineNo);
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
                outputLineNo += physicalCount;
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
            Deque<CondFrame> condStack, StringBuilder out, boolean active,
            LineState lineState, int physicalCount) {
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
            handleInclude(rest, file, lineNo, out, lineState, physicalCount);
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
            handleLine(rest, file, lineNo, lineState, physicalCount);
        }
        else {
            errorHandler.error(file.getPath() + ":" + lineNo + ": unknown preprocessor directive: #" + name);
        }
    }

    /** #line NUMBER ["FILENAME"]: adjusts what __LINE__/__FILE__ -- and,
     *  via the LineMap breakpoint added below, everything else the
     *  compiler reports too -- report from the *next* physical line
     *  onward. Scoped to the current file only (see LineState) -- an
     *  #include'd file with its own #line doesn't affect the file that
     *  included it, and returning from it restores whatever delta the
     *  includer already had, matching a real preprocessor's own per-file
     *  #line scoping. */
    private void handleLine(String rest, File file, int lineNo, LineState lineState,
            int physicalCount) {
        List<Tok> toks = new PPLexer().tokenize(rest);
        expander.setLocation(file.getPath(), lineNo);
        List<Tok> expanded = expander.expand(toks);
        List<Tok> noWs = new ArrayList<Tok>();
        for (Tok t : expanded) {
            if (!t.isWs()) noWs.add(t);
        }
        if (noWs.isEmpty() || noWs.get(0).kind != Tok.Kind.NUMBER) {
            errorHandler.error(file.getPath() + ":" + lineNo + ": #line requires a line number");
            return;
        }
        long newLine;
        try {
            newLine = Long.parseLong(noWs.get(0).text);
        }
        catch (NumberFormatException ex) {
            errorHandler.error(file.getPath() + ":" + lineNo
                    + ": invalid #line number: " + noWs.get(0).text);
            return;
        }
        if (noWs.size() > 1) {
            if (noWs.get(1).kind == Tok.Kind.STRING) {
                lineState.reportedFile = unquote(noWs.get(1).text);
            }
            else {
                errorHandler.error(file.getPath() + ":" + lineNo + ": malformed #line filename");
            }
        }
        // The *next* physical line (lineNo + 1) should report as newLine.
        // It'll be appended right after this #line directive's own
        // (still-pending) placeholder lines, hence the "+ physicalCount".
        lineState.lineDelta = newLine - (lineNo + 1);
        lineMap.addBreakpoint(outputLineNo + physicalCount + 1, lineState.reportedFile, (int) newLine);
    }

    /** Undoes stringify()'s escaping of '"'/'\\' and strips the
     *  surrounding quotes -- used for #line's filename operand and
     *  _Pragma's string operand (C99 6.10.9's own "destringizing"). */
    private static String unquote(String text) {
        String inner = text.substring(1, text.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()
                    && (inner.charAt(i + 1) == '"' || inner.charAt(i + 1) == '\\')) {
                i++;
                c = inner.charAt(i);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** C99 6.10.9's _Pragma(STRING) operator, recognized anywhere it
     *  appears in already macro-expanded text -- including text a macro
     *  itself produced (e.g. "#define DO_PRAGMA(x) _Pragma(#x)"), since
     *  by the time this runs, expand() has already fully expanded that
     *  call. With no pragma this compiler actually acts on (see
     *  #pragma's own handling above), a well-formed one is simply
     *  equivalent to an ignored #pragma and vanishes; its argument is
     *  still destringized and thrown away rather than skipped
     *  altogether, so a malformed one (e.g. missing its closing paren)
     *  is still caught as an error instead of silently passed through
     *  to the parser as raw, meaningless tokens. */
    private List<Tok> stripPragmaOperator(List<Tok> in, File file, int lineNo) {
        boolean any = false;
        for (Tok t : in) {
            if (t.isIdent() && t.text.equals("_Pragma")) {
                any = true;
                break;
            }
        }
        if (!any) {
            return in;
        }
        List<Tok> out = new ArrayList<Tok>();
        int i = 0;
        while (i < in.size()) {
            Tok t = in.get(i);
            if (t.isIdent() && t.text.equals("_Pragma")) {
                int j = i + 1;
                while (j < in.size() && in.get(j).isWs()) j++;
                if (j < in.size() && in.get(j).isPunct("(")) {
                    j++;
                    while (j < in.size() && in.get(j).isWs()) j++;
                    if (j < in.size() && in.get(j).kind == Tok.Kind.STRING) {
                        unquote(in.get(j).text);  // destringized, then discarded -- see doc comment
                        j++;
                        while (j < in.size() && in.get(j).isWs()) j++;
                        if (j < in.size() && in.get(j).isPunct(")")) {
                            i = j + 1;
                            continue;
                        }
                    }
                }
                errorHandler.error(file.getPath() + ":" + lineNo + ": malformed _Pragma operator");
            }
            out.add(t);
            i++;
        }
        return out;
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
        boolean variadic = false;
        List<String> params = new ArrayList<String>();
        if (i < toks.size() && toks.get(i).isPunct("(")) {
            functionLike = true;
            i++;
            while (i < toks.size() && !toks.get(i).isPunct(")")) {
                Tok t = toks.get(i);
                if (t.isWs() || t.isPunct(",")) {
                    i++;
                }
                else if (t.isPunct(".")) {
                    // "..." lexes as three separate "." tokens (see
                    // PPLexer) -- any of the three latches variadic.
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
        }
        while (i < toks.size() && toks.get(i).isWs()) i++;
        List<Tok> body = new ArrayList<Tok>(toks.subList(i, toks.size()));
        while (!body.isEmpty() && body.get(body.size() - 1).isWs()) {
            body.remove(body.size() - 1);
        }
        macros.put(name, new Macro(name, functionLike, variadic, params, body));
    }

    private void handleInclude(String rest, File file, int lineNo, StringBuilder out,
            LineState lineState, int physicalCount) {
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
            // Resume mapping to the including file's own continuing
            // lines -- the recursive processFile() call above just
            // lines it contributed. The #include line's own placeholder
            // (physicalCount lines) hasn't been appended yet -- it comes
            // right after this, back in the outer loop -- so skip past
            // it too.
            lineMap.addBreakpoint(outputLineNo + physicalCount + 1, lineState.reportedFile,
                    (int) (lineNo + 1 + lineState.lineDelta));
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
