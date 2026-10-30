package dev.gaius.classlib;

/** Focused checks for the WebAssembly TString.equals fast path. */
public final class StringFastPath {
    private StringFastPath() {
    }

    public static void main(String[] args) {
        String left = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n', 'e'});
        String right = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n', 'e'});
        String prefix = new String(new char[] {'m', 'i', 'n', 'e', 'c', 'r', 'a', 'f', 't', ':', 's', 't', 'o', 'n'});
        System.out.println("identity=" + (left == left));
        System.out.println("equal=" + left.equals(right) + ",reverse=" + right.equals(left));
        System.out.println("prefix=" + left.equals(prefix) + ",reverse=" + prefix.equals(left));
        System.out.println("collision=" + "Aa".equals("BB") + ",hash=" + ("Aa".hashCode() == "BB".hashCode()));
        System.out.println("unicode=" + new String(new char[] {'世', '界', '\ud83d', '\ude00'}).equals("世界😀"));
    }
}
