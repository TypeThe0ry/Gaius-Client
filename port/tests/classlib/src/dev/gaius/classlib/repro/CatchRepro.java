package dev.gaius.classlib.repro;

/** Shape of SynchedEntityData.defineId: a typed catch around a reflective lookup. */
public final class CatchRepro {
    static boolean debug;
    static int counter;

    private CatchRepro() {
    }

    static void report(Object a, Object b, Object c) {
        System.out.println(a + " " + b + " " + c);
    }

    static Class<?> lookup(String name) throws ClassNotFoundException {
        if (counter > 1000) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return Class.forName(name);
    }

    static String define(Class<?> type, Object serializer) {
        if (debug) {
            try {
                Class<?> caller = lookup(Thread.currentThread().getStackTrace()[2].getClassName());
                if (!caller.equals(type)) {
                    report(type, caller, new RuntimeException());
                }
            } catch (ClassNotFoundException e) {
            }
        }
        int id = counter++;
        if (id > 254) {
            throw new IllegalArgumentException("Data value id is too big with " + id);
        }
        return serializer.toString() + id;
    }

    public static void main(String[] args) {
        debug = args.length > 3;
        System.out.println(define(String.class, "a"));
        System.out.println(define(Integer.class, "b"));
    }
}
