package dev.gaius.classlib;


/**
 * Float.toString/Double.toString (JDK 19+: the shortest decimal that rounds back, Raffaello
 * Giulietti's algorithm), string concatenation, and parsing. Vanilla prints floats in the debug
 * screen, NBT/SNBT, JSON, options.txt and commands, and parses them back.
 */
final class FloatStrings {
    private FloatStrings() {
    }

    static void run() {
        double[] doubles = {0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.1 + 0.2, 1.0 / 3, 2.0 / 3, 100.0, 1e7, 9999999.0,
            1.0E-3, 1.0E-4, 123456789.0, 1e21, 1e22, 1e23, 2e23, 5e-324, Double.MIN_NORMAL, Double.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.PI, Math.E, 0.30000000000000004,
            4.35, 1.1, 2.2, 3.3, 8.41, 0.001, 1.0E-5, 12345678.9, 1.7976931348623157E308, 4.9E-324, 2.0E-3,
            9.007199254740993E15, 9.007199254740992E15, 1.0E15, 1.0E16, 123.456, -0.0001234, 2.5, 3.5,
            64.0, 0.5, 0.25, 0.125, 1e-7, 5e-5, 6.02214076e23, 1.602176634e-19};
        for (double d : doubles) {
            String key = Out.hex(d);
            Out.check("d." + key, () -> Double.toString(d));
            Out.check("d.concat." + key, () -> "" + d);
            Out.check("d.hex." + key, () -> Double.toHexString(d));
            Out.check("d.roundTrip." + key, () -> Out.hex(Double.parseDouble(Double.toString(d))));
        }
        float[] floats = {0f, -0f, 1f, 0.1f, 0.2f, 0.3f, 1f / 3, 1.1f, 3.4028235E38f, 1.4E-45f, 1.17549435E-38f,
            1.0E10f, 16777216f, 16777217f, 1e7f, 9999999f, 1e-3f, 1e-4f, 59.95f, 0.05f, 2.5f, 123.456f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, (float) Math.PI, 1.0E-5f, 3.3f,
            2.0E-3f, 8.0E-3f, 4.4E-3f, 1.0E23f, 20000000f, 0.33333334f, 100f, 0.9f};
        for (float f : floats) {
            String key = Out.hex(f);
            Out.check("f." + key, () -> Float.toString(f));
            Out.check("f.concat." + key, () -> "" + f);
            Out.check("f.widened." + key, () -> Double.toString(f));
            Out.check("f.roundTrip." + key, () -> Out.hex(Float.parseFloat(Float.toString(f))));
        }
        Rand random = new Rand(2026);
        Out.Digest dDigest = new Out.Digest();
        Out.Digest fDigest = new Out.Digest();
        for (int i = 0; i < 2000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            float f = Float.intBitsToFloat(random.nextInt());
            if (i < 160) {
                if (!Double.isNaN(d)) {
                    Out.check("d.random." + Out.hex(d), () -> Double.toString(d));
                }
                if (!Float.isNaN(f)) {
                    Out.check("f.random." + Out.hex(f), () -> Float.toString(f));
                }
            }
            dDigest.add(Double.toString(d));
            fDigest.add(Float.toString(f));
            double scaled = (random.nextDouble() - 0.5) * Rand.pow10(random.nextInt(40) - 20);
            dDigest.add(Double.toString(scaled));
            fDigest.add(Float.toString((float) scaled));
        }
        Out.put("d.random.digest", dDigest);
        Out.put("f.random.digest", fDigest);
        String[] parse = {"1", "-1.5", "  12.5  ", "1e400", "-1e-400", "0x1.8p1", "0x10", "1_000", "NaN", "-Infinity",
            "Infinity", "1.", ".5", "1e", "+.5e-3", "1d", "1f", "2.2250738585072012e-308", "1e-46",
            "3.4028236e38", "0.1", "9007199254740993", "", "-", "1,5", "١"};
        for (int i = 0; i < parse.length; i++) {
            String s = parse[i];
            Out.check("parseDouble." + i, () -> Out.show(Double.parseDouble(s)));
            Out.check("parseFloat." + i, () -> Out.show(Float.parseFloat(s)));
            Out.check("doubleValueOf." + i, () -> Out.show(Double.valueOf(s)));
        }
    }
}
