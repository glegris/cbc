package net.loveruby.cflat.compiler;
import net.loveruby.cflat.parser.Parser;
import net.loveruby.cflat.ast.AST;
import net.loveruby.cflat.ast.StmtNode;
import net.loveruby.cflat.ast.ExprNode;
import net.loveruby.cflat.type.TypeTable;
import net.loveruby.cflat.ir.IR;
import net.loveruby.cflat.sysdep.CodeGenerator;
import net.loveruby.cflat.sysdep.AssemblyCode;
import net.loveruby.cflat.sysdep.BinaryAssemblyCode;
import net.loveruby.cflat.sysdep.jvm.JVMAssemblyCode;
import net.loveruby.cflat.utils.ErrorHandler;
import net.loveruby.cflat.exception.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.io.*;

public class Compiler {
    // #@@range/main{
    static final public String ProgramName = "cbc";
    static final public String Version = "1.0.0";

    static public void main(String[] args) {
        new Compiler(ProgramName).commandMain(args);
    }

    private final ErrorHandler errorHandler;

    public Compiler(String programName) {
        this.errorHandler = new ErrorHandler(programName);
    }
    // #@@}

    public void commandMain(String[] args) {
        Options opts = parseOptions(args);
        if (opts.mode() == CompilerMode.CheckSyntax) {
            System.exit(checkSyntax(opts) ? 0 : 1);
        }
        if (opts.mode() == CompilerMode.PreprocessOnly) {
            System.exit(preprocessOnly(opts) ? 0 : 1);
        }
        try {
            List<SourceFile> srcs = opts.sourceFiles();
            build(srcs, opts);
            System.exit(0);
        }
        catch (CompileException ex) {
            errorHandler.error(ex.getMessage());
            System.exit(1);
        }
    }

    private Options parseOptions(String[] args) {
        try {
            return Options.parse(args);
        }
        catch (OptionParseError err) {
            errorHandler.error(err.getMessage());
            errorHandler.error("Try \"cbc --help\" for usage");
            System.exit(1);
            return null;   // never reach
        }
    }

    private boolean checkSyntax(Options opts) {
        boolean failed = false;
        for (SourceFile src : opts.sourceFiles()) {
            if (isValidSyntax(src.path(), opts)) {
                System.out.println(src.path() + ": Syntax OK");
            }
            else {
                System.out.println(src.path() + ": Syntax Error");
                failed = true;
            }
        }
        return !failed;
    }

    private boolean preprocessOnly(Options opts) {
        boolean failed = false;
        for (SourceFile src : opts.sourceFiles()) {
            try {
                String result = new net.loveruby.cflat.cpp.Preprocessor(
                        opts.includePaths(), errorHandler)
                        .preprocessFile(new File(src.path()));
                if (errorHandler.errorOccured()) {
                    failed = true;
                }
                else {
                    System.out.print(result);
                }
            }
            catch (FileException ex) {
                errorHandler.error(ex.getMessage());
                failed = true;
            }
        }
        return !failed;
    }

    private boolean isValidSyntax(String path, Options opts) {
        try {
            parseFile(path, opts);
            return true;
        }
        catch (SyntaxException ex) {
            return false;
        }
        catch (FileException ex) {
            errorHandler.error(ex.getMessage());
            return false;
        }
    }

    // #@@range/build{
    public void build(List<SourceFile> srcs, Options opts)
                                        throws CompileException {
        for (SourceFile src : srcs) {
            if (src.isCflatSource()) {
                String destPath = opts.asmFileNameOf(src);
                compile(src.path(), destPath, opts);
                src.setCurrentName(destPath);
            }
            if (! opts.isAssembleRequired()) continue;
            if (src.isAssemblySource()) {
                String destPath = opts.objFileNameOf(src);
                assemble(src.path(), destPath, opts);
                src.setCurrentName(destPath);
            }
        }
        if (! opts.isLinkRequired()) return;
        link(opts);
    }
    // #@@}

    public void compile(String srcPath, String destPath,
                        Options opts) throws CompileException {
        AST ast = parseFile(srcPath, opts);
        if (dumpAST(ast, opts.mode())) return;
        TypeTable types = opts.typeTable();
        AST sem = semanticAnalyze(ast, types, opts);
        if (dumpSemant(sem, opts.mode())) return;
        IR ir = new IRGenerator(types, errorHandler).generate(sem);
        if (dumpIR(ir, opts.mode())) return;
        AssemblyCode asm = generateAssembly(ir, opts);
        if (dumpAsm(asm, opts.mode())) return;
        if (printAsm(asm, opts.mode())) return;
        if (errorHandler.errorOccured()) {
            throw new SemanticException("compile failed.");
        }
        writeAssembly(destPath, asm);
        writeNativeRuntimeSource(destPath, asm);
    }

    /** JVM backend only: writes/updates "NativeRuntime.java" next to
     *  destPath, then compiles it with javac so "NativeRuntime.class" is
     *  ready to go alongside it (see CodeGenerator#nativeRuntimeSource()
     *  and NativeRuntime's own generated class doc for the overall
     *  mechanism). This is no longer optional the way it used to be:
     *  the compiled program now extends NativeRuntime directly, so it
     *  can't even be loaded without NativeRuntime.class present.  Never
     *  overwrites an existing NativeRuntime.java -- a user's hand-written
     *  implementations in it must survive recompiling the .cb file --
     *  but does warn about any stub name the existing file doesn't seem
     *  to define, since otherwise a missing one only shows up as a
     *  NoSuchMethodError at run time; either way, it's (re)compiled with
     *  javac so edits take effect without a separate manual step. */
    private void writeNativeRuntimeSource(String destPath, AssemblyCode asm)
            throws FileException {
        if (!(asm instanceof JVMAssemblyCode)) {
            return;
        }
        JVMAssemblyCode jvmAsm = (JVMAssemblyCode) asm;
        String source = jvmAsm.nativeRuntimeSource();
        if (source == null) {
            return;
        }
        File dir = new File(destPath).getAbsoluteFile().getParentFile();
        File file = new File(dir, "NativeRuntime.java");
        if (!file.exists()) {
            writeFile(file.getPath(), source);
            if (!jvmAsm.nativeStubNames().isEmpty()) {
                errorHandler.warn(file.getPath() + ": generated with stub(s) for "
                        + jvmAsm.nativeStubNames() + " -- edit it to implement "
                        + "them, then re-run cbc to recompile it");
            }
        }
        else {
            String existing;
            try {
                existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            catch (IOException ex) {
                throw new FileException(file.getPath() + ": could not check it for "
                        + "missing native stubs: " + ex.getMessage());
            }
            List<String> missing = new ArrayList<String>();
            for (String name : jvmAsm.nativeStubNames()) {
                if (!existing.contains(name)) {
                    missing.add(name);
                }
            }
            if (!missing.isEmpty()) {
                errorHandler.warn(file.getPath() + ": missing an implementation "
                        + "for " + missing + " (not overwriting your existing "
                        + "file -- add these to it by hand)");
            }
        }
        compileNativeRuntime(file);
    }

    /** Compiles NativeRuntime.java with javac, using this very process's
     *  own classpath (which already has StandardRuntime.class on it,
     *  since cbc itself was built from the same source tree -- see
     *  bin/build.sh) so it can resolve the "extends StandardRuntime".
     *  Always safe to run unconditionally: freshly generated source
     *  compiles as-is (an unimplemented stub is valid Java, it just
     *  throws NotImplementedException), and re-running it after a user
     *  edit is exactly how those edits take effect. */
    private void compileNativeRuntime(File file) throws FileException {
        String classpath = System.getProperty("java.class.path");
        Process proc;
        try {
            proc = new ProcessBuilder("javac", "-cp", classpath,
                    "-d", file.getParentFile().getPath(), file.getPath())
                    .redirectErrorStream(true)
                    .start();
        }
        catch (IOException ex) {
            throw new FileException(file.getPath() + ": could not run javac: " + ex.getMessage());
        }
        String output;
        try {
            output = readAll(proc.getInputStream());
            proc.waitFor();
        }
        catch (IOException ex) {
            throw new FileException(file.getPath() + ": could not read javac's output: " + ex.getMessage());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FileException(file.getPath() + ": javac was interrupted");
        }
        if (proc.exitValue() != 0) {
            throw new FileException(file.getPath() + ": javac failed:\n" + output);
        }
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public AST parseFile(String path, Options opts)
                            throws SyntaxException, FileException {
        return Parser.parseFile(new File(path),
                opts.includePaths(), errorHandler, opts.doesDebugParser());
    }

    public AST semanticAnalyze(AST ast, TypeTable types,
                Options opts) throws SemanticException {
        new LocalResolver(errorHandler).resolve(ast);
        new TypeResolver(types, errorHandler).resolve(ast);
        types.semanticCheck(errorHandler);
        if (opts.mode() == CompilerMode.DumpReference) {
            ast.dump();
            return ast;
        }
        new DereferenceChecker(types, errorHandler).check(ast);
        new TypeChecker(types, errorHandler).check(ast);
        return ast;
    }

    public AssemblyCode generateAssembly(IR ir, Options opts) {
        return opts.codeGenerator(errorHandler).generate(ir);
    }

    // #@@range/assemble{
    public void assemble(String srcPath, String destPath,
                            Options opts) throws IPCException {
        opts.assembler(errorHandler)
            .assemble(srcPath, destPath, opts.asOptions());
    }
    // #@@}

    // #@@range/link{
    public void link(Options opts) throws IPCException {
        if (! opts.isGeneratingSharedLibrary()) {
            generateExecutable(opts);
        }
        else {
            generateSharedLibrary(opts);
        }
    }
    // #@@}

    // #@@range/generateExecutable{
    public void generateExecutable(Options opts) throws IPCException {
        opts.linker(errorHandler).generateExecutable(
                opts.ldArgs(), opts.exeFileName(), opts.ldOptions());
    }
    // #@@}

    // #@@range/generateSharedLibrary{
    public void generateSharedLibrary(Options opts) throws IPCException {
        opts.linker(errorHandler).generateSharedLibrary(
                opts.ldArgs(), opts.soFileName(), opts.ldOptions());
    }
    // #@@}

    private void writeAssembly(String path, AssemblyCode asm) throws FileException {
        if (asm instanceof BinaryAssemblyCode) {
            writeBinaryFile(path, ((BinaryAssemblyCode)asm).toBytes());
        }
        else {
            writeFile(path, asm.toSource());
        }
    }

    private void writeBinaryFile(String path, byte[] data) throws FileException {
        if (path.equals("-")) {
            try {
                System.out.write(data);
                System.out.flush();
            }
            catch (IOException ex) {
                errorHandler.error("IO error" + ex.getMessage());
                throw new FileException("file error");
            }
            return;
        }
        try {
            FileOutputStream f = new FileOutputStream(path);
            try {
                f.write(data);
            }
            finally {
                f.close();
            }
        }
        catch (FileNotFoundException ex) {
            errorHandler.error("file not found: " + path);
            throw new FileException("file error");
        }
        catch (IOException ex) {
            errorHandler.error("IO error" + ex.getMessage());
            throw new FileException("file error");
        }
    }

    private void writeFile(String path, String str) throws FileException {
        if (path.equals("-")) {
            System.out.print(str);
            return;
        }
        try {
            BufferedWriter f = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path)));
            try {
                f.write(str);
            }
            finally {
                f.close();
            }
        }
        catch (FileNotFoundException ex) {
            errorHandler.error("file not found: " + path);
            throw new FileException("file error");
        }
        catch (IOException ex) {
            errorHandler.error("IO error" + ex.getMessage());
            throw new FileException("file error");
        }
    }

    private boolean dumpAST(AST ast, CompilerMode mode) {
        switch (mode) {
        case DumpTokens:
            ast.dumpTokens(System.out);
            return true;
        case DumpAST:
            ast.dump();
            return true;
        case DumpStmt:
            findStmt(ast).dump();
            return true;
        case DumpExpr:
            findExpr(ast).dump();
            return true;
        default:
            return false;
        }
    }

    private StmtNode findStmt(AST ast) {
        StmtNode stmt = ast.getSingleMainStmt();
        if (stmt == null) {
            errorExit("source file does not contains main()");
        }
        return stmt;
    }

    private ExprNode findExpr(AST ast) {
        ExprNode expr = ast.getSingleMainExpr();
        if (expr == null) {
            errorExit("source file does not contains single expression");
        }
        return expr;
    }

    private boolean dumpSemant(AST ast, CompilerMode mode) {
        switch (mode) {
        case DumpReference:
            return true;
        case DumpSemantic:
            ast.dump();
            return true;
        default:
            return false;
        }
    }

    private boolean dumpIR(IR ir, CompilerMode mode) {
        if (mode == CompilerMode.DumpIR) {
            ir.dump();
            return true;
        }
        else {
            return false;
        }
    }

    private boolean dumpAsm(AssemblyCode asm, CompilerMode mode) {
        if (mode == CompilerMode.DumpAsm) {
            asm.dump(System.out);
            return true;
        }
        else {
            return false;
        }
    }

    private boolean printAsm(AssemblyCode asm, CompilerMode mode) {
        if (mode == CompilerMode.PrintAsm) {
            System.out.print(asm.toSource());
            return true;
        }
        else {
            return false;
        }
    }

    private void errorExit(String msg) {
        errorHandler.error(msg);
        System.exit(1);
    }
}
