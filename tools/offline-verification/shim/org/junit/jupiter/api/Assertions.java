package org.junit.jupiter.api;
import java.util.Objects;
/** LOCAL VERIFICATION SHIM ONLY - mirrors the subset of the JUnit 5 Assertions API used by the domain tests. */
public final class Assertions {
  @FunctionalInterface public interface Executable { void execute() throws Throwable; }
  private static void fail(String m) { throw new AssertionError(m); }
  public static void assertEquals(int e, int a) { if (e != a) fail("expected " + e + " but was " + a); }
  public static void assertEquals(long e, long a) { if (e != a) fail("expected " + e + " but was " + a); }
  public static void assertEquals(long e, Long a) { assertEquals((Object) e, (Object) a); }
  public static void assertEquals(Long e, long a) { assertEquals((Object) e, (Object) a); }
  public static void assertEquals(Long e, Long a) { assertEquals((Object) e, (Object) a); }
  public static void assertEquals(Object e, Object a) { if (!Objects.equals(e, a)) fail("expected <" + e + "> but was <" + a + ">"); }
  public static void assertNotEquals(Object u, Object a) { if (Objects.equals(u, a)) fail("expected not equal to <" + u + ">"); }
  public static void assertTrue(boolean c) { if (!c) fail("expected true"); }
  public static void assertTrue(boolean c, String m) { if (!c) fail(m); }
  public static void assertFalse(boolean c) { if (c) fail("expected false"); }
  public static void assertFalse(boolean c, String m) { if (c) fail(m); }
  public static <T extends Throwable> T assertThrows(Class<T> type, Executable ex) {
    try { ex.execute(); } catch (Throwable t) {
      if (type.isInstance(t)) return type.cast(t);
      fail("expected " + type.getName() + " but got " + t);
    }
    fail("expected " + type.getName() + " but nothing was thrown"); return null;
  }
}
