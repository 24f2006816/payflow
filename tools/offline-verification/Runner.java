import java.lang.reflect.*; import java.nio.file.*; import java.util.*; import java.util.stream.*;
public class Runner {
  public static void main(String[] a) throws Exception {
    List<String> classes; try (Stream<Path> s = Files.walk(Path.of(a[0]))) {
      classes = s.filter(p -> p.toString().endsWith("Test.class")).map(p -> Path.of(a[0]).relativize(p).toString()
        .replace(".class","").replace('/', '.')).sorted().collect(Collectors.toList()); }
    int pass = 0, fail = 0;
    for (String cn : classes) { Class<?> c = Class.forName(cn);
      for (Method m : c.getDeclaredMethods()) { if (!m.isAnnotationPresent(org.junit.jupiter.api.Test.class)) continue;
        Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true); m.setAccessible(true);
        try { m.invoke(k.newInstance()); pass++; System.out.println("PASS " + c.getSimpleName() + "." + m.getName()); }
        catch (InvocationTargetException e) { fail++; System.out.println("FAIL " + c.getSimpleName() + "." + m.getName() + ": " + e.getCause()); } } }
    System.out.println("Domain unit tests: " + pass + " passed, " + fail + " failed"); System.exit(fail == 0 ? 0 : 1);
  }
}
