package dev.gaius.browser;

public final class BrowserStrictMath {
  private BrowserStrictMath() {}
  public static float round(float value) { return Math.round(value); }
  public static float fromDouble(double value) { return (float) value; }
  public static float fromInt(int value) { return value; }
  public static float fromLong(long value) { return value; }
  public static int toInt(double value) { return (int) value; }
  public static long toLong(double value) { return (long) value; }
  public static double sum(java.util.stream.DoubleStream stream) { return stream.sum(); }
}
