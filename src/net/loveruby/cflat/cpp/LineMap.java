package net.loveruby.cflat.cpp;

import java.util.ArrayList;
import java.util.List;

/** Maps a line number in Preprocessor's flattened output buffer back to
 *  the (file, line) pair that should be reported for it. Preprocessor
 *  flattens an entire translation unit -- the top-level file and every
 *  file it #includes, transitively -- into one text blob, so a raw line
 *  number in that blob (which is all the parser's lexer ever sees) means
 *  nothing to a human: it's some file's own line count PLUS however many
 *  lines everything spliced in before it contributed. This map lets
 *  Parser#location recover the actual originating file and line, so
 *  error messages name the file that really has the mistake instead of
 *  always naming the top-level file being compiled.
 *
 * Built incrementally by Preprocessor as it flattens (see its
 * addBreakpoint call sites): once at the start of every file it enters
 * (the top-level file, and each #include, recursively), once when
 * control returns from an #include back to the includer, and once at
 * every #line directive. Between two breakpoints, the mapping is a
 * simple constant offset, since Preprocessor always emits exactly one
 * output line per input physical line. */
public class LineMap {
    private static class Breakpoint {
        final int outputLine;   // 1-based line number in the flattened buffer
        final String file;
        final int reportedLine; // what outputLine itself should report as

        Breakpoint(int outputLine, String file, int reportedLine) {
            this.outputLine = outputLine;
            this.file = file;
            this.reportedLine = reportedLine;
        }
    }

    private final List<Breakpoint> breakpoints = new ArrayList<Breakpoint>();

    /** Records that, starting at outputLine (until the next breakpoint,
     *  if any), output line N reports as (file, reportedLine + (N -
     *  outputLine)). Breakpoints are always added in non-decreasing
     *  outputLine order (Preprocessor builds this map in a single
     *  forward pass); a second breakpoint at the same outputLine (e.g.
     *  an empty #include'd file, entered and immediately left without
     *  ever contributing a line) simply supersedes the first, since
     *  nothing was ever attributed to it. */
    public void addBreakpoint(int outputLine, String file, int reportedLine) {
        if (!breakpoints.isEmpty()
                && breakpoints.get(breakpoints.size() - 1).outputLine == outputLine) {
            breakpoints.remove(breakpoints.size() - 1);
        }
        breakpoints.add(new Breakpoint(outputLine, file, reportedLine));
    }

    /** The file that outputLine should be reported as belonging to, or
     *  null if outputLine precedes any recorded breakpoint (should not
     *  happen for a line the parser actually sees). */
    public String fileAt(int outputLine) {
        Breakpoint b = breakpointAt(outputLine);
        return b == null ? null : b.file;
    }

    /** The line number outputLine should be reported as, or outputLine
     *  itself if it precedes any recorded breakpoint. */
    public int lineAt(int outputLine) {
        Breakpoint b = breakpointAt(outputLine);
        return b == null ? outputLine : b.reportedLine + (outputLine - b.outputLine);
    }

    private Breakpoint breakpointAt(int outputLine) {
        Breakpoint result = null;
        for (Breakpoint b : breakpoints) {
            if (b.outputLine > outputLine) break;
            result = b;
        }
        return result;
    }
}
