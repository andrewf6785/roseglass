package dev.roseglass;

/** One coalesced job per column; pending bits represent sections, not duplicate queue entries. */
final class SectionWork {
    static final int ALL = 0xFFF8;
    int pending;
    private int checks, forced;
    private long firstTick, dueTick;

    void queue(int mask) { pending |= mask & ALL; }
    void invalidate(int mask, boolean force, long tick) {
        mask &= ALL;
        if (mask == 0) return;
        if (checks == 0) firstTick = tick;
        checks |= mask;
        if (force) forced |= mask;
        // Briefly combine bursts, but sustained notifications cannot postpone work indefinitely.
        dueTick = Math.min(tick + 2, firstTick + 6);
    }
    boolean due(long tick) { return checks != 0 && tick >= dueTick; }
    boolean checking() { return checks != 0; }
    int requested() { return checks; }
    int forced() { return forced; }
    int outstanding() { return pending | checks; }
    void checked() { checks = forced = 0; }
    void cancel() { pending = checks = forced = 0; }
    int next() { return pending == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(pending); }
    void complete(int section) { pending &= ~(1 << section); }
}
