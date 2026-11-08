import com.sun.source.util.JavacTask;
import javax.tools.*; import java.nio.file.*; import java.util.*; import java.util.stream.*;
/** Parse-only check (no symbol resolution): catches syntax errors in files whose dependencies are unavailable. */
public class SyntaxCheck {
  public static void main(String[] a) throws Exception {
    List<Path> files; try (Stream<Path> s = Files.walk(Path.of(a[0]))) { files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    JavaCompiler c = ToolProvider.getSystemJavaCompiler(); DiagnosticCollector<JavaFileObject> d = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = c.getStandardFileManager(d, null, null)) {
      JavacTask t = (JavacTask) c.getTask(null, fm, d, List.of("--release", "21"), null, fm.getJavaFileObjectsFromPaths(files));
      t.parse();
    }
    d.getDiagnostics().forEach(x -> System.out.println(x.getKind() + " " + x.getSource().getName() + ":" + x.getLineNumber() + " " + x.getMessage(null)));
    long errors = d.getDiagnostics().stream().filter(x -> x.getKind() == Diagnostic.Kind.ERROR).count();
    System.out.println("Parsed " + files.size() + " files, syntax errors: " + errors); System.exit(errors == 0 ? 0 : 1);
  }
}
