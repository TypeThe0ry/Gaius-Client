package dev.gaius.browser;

/**
 * Lets a deadline check that runs on every call read the clock only now and then.
 *
 * <p>The checks this serves ask one question, "has this budget run out yet?", and many of them
 * run thousands of times per second, each call costing a few microseconds of work. A clock read
 * from WebAssembly is a call into the browser (a quarter of a microsecond), so reading it before
 * every call is a measurable part of the thread. Reading it less often must not make the budget
 * run over: the budget has to end after about the time it says, not after "some calls".
 *
 * <p>The stride therefore never counts calls blindly. At each read the caller passes the
 * position of the clock on its own axis (any unit, any origin; only differences count) and the
 * time left until the deadline. From the positions of two consecutive reads and the number of
 * calls between them the stride keeps the cost of one call, as the larger of the latest average
 * and a slowly decaying peak (the cost rises at once and falls by {@link #DECAY} per read), and
 * lets the next calls pass without a read as long as their expected cost stays within
 * {@code 1/SAFETY} of the time left, at most {@link #MAX_SKIP} of them. With {@code R} left and
 * a call cost of {@code c} the next read comes after {@code min(MAX_SKIP, R / (4 c))} calls, so
 * the reads thin out in the middle of a budget and come back to one per call when it is nearly
 * used up: the call that crosses the deadline is the call that notices it, as when every call
 * reads.
 *
 * <p>Worst case: the stride notices a deadline late only when the calls it let pass cost more
 * than four times what the latest reads predicted; the delay is then at most {@code MAX_SKIP}
 * calls beyond the one that crossed the deadline. It knows nothing before it has seen two
 * reads, after {@link #reset()}, or when the clock axis went backwards, and then asks for a read
 * on every call.
 *
 * <p>The class keeps no count of calls of its own: the caller owns the countdown (which the
 * smoke tests of the scheduler also set directly) and passes the interval {@link #observe}
 * returned for the previous read.
 */
public final class ClockStride {
    /** Calls that may pass between two reads, at most. */
    public static final int MAX_SKIP = 7;
    /** The calls skipped are expected to use at most {@code 1/SAFETY} of the time left. */
    public static final double SAFETY = 4.0;
    /** Per read, the fraction of the peak call cost that is kept. */
    public static final double DECAY = 0.875;

    private boolean hasBase;
    private boolean hasCost;
    private double lastAt;
    private int interval = 1;
    private double cost;

    /**
     * Records a clock read and returns the number of calls until the next one, counting the call
     * that reads (1: read on the next call, 8: let seven calls pass first).
     *
     * @param position where the clock stood at this read; the unit is the caller's, and must
     *     be the one of {@code remaining}
     * @param remaining time left until the deadline; a deadline that has passed asks for a read
     *     on every call, and so does a caller that does not call this
     */
    public int observe(double position, double remaining) {
        if (hasBase && position >= lastAt) {
            double perCall = (position - lastAt) / interval;
            cost = hasCost ? Math.max(perCall, cost * DECAY) : perCall;
            hasCost = true;
        }
        // An axis that moved backwards (a new origin) leaves what was learnt as it is and the
        // interval just ended says nothing: the position below becomes the new base.
        lastAt = position;
        hasBase = true;
        int next = 1;
        if (hasCost && remaining > 0.0) {
            double allowed = remaining / (SAFETY * cost);
            next = allowed >= MAX_SKIP ? MAX_SKIP + 1 : 1 + (int) allowed;
        }
        interval = next;
        return next;
    }

    /**
     * Forgets the position of the last read (a new budget, a yield, a pause of the clock axis),
     * so that time that was not spent in calls never counts as their cost. The cost learnt
     * so far stays: it is what the next calls are measured against.
     */
    public void reset() {
        hasBase = false;
        interval = 1;
    }

    /** Drops everything learnt, as a fresh stride (tests). */
    public void forget() {
        hasBase = false;
        hasCost = false;
        interval = 1;
        cost = 0.0;
        lastAt = 0.0;
    }

    /** The current estimate of the cost of one call, or 0 when none is known (tests). */
    public double cost() {
        return hasCost ? cost : 0.0;
    }
}
