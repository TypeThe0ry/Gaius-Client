package dev.gaius.browser;

/** JVM smoke stub: browser cooperative scheduling is inactive on the JVM. */
public final class BrowserStartupScheduler {
  private BrowserStartupScheduler() {}
  public static void blockRegistered() {}
  public static void blockStateInitialized() {}
  public static void registryBootstrapCompleted() {}
  public static void datapackResourceDecoded() {}
  public static void phase(String ignored) {}
  public static void complete() {}
}
