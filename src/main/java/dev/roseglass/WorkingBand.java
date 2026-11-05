package dev.roseglass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tracks three perimeter rings; unchanged views do no enumeration or provider lookups. */
final class WorkingBand {
    static final class Delta {
        final List<Long> entered, exited;
        final boolean changed;
        Delta(List<Long> entered, List<Long> exited, boolean changed) { this.entered = entered; this.exited = exited; this.changed = changed; }
    }
    private static final Delta EMPTY = new Delta(Collections.emptyList(), Collections.emptyList(), false);
    private Set<Long> active = new HashSet<>();
    private int x, z, radius, visual;
    private boolean initialized, enabled;

    boolean contains(long key) { return active.contains(key); }
    void clear() { active.clear(); initialized = false; }
    Delta update(int x, int z, int radius, int visual, boolean enabled) {
        if (initialized && this.x == x && this.z == z && this.radius == radius
                && this.visual == visual && this.enabled == enabled) return EMPTY;
        Set<Long> next = new HashSet<>();
        if (enabled) for (int ring = Math.max(0, radius - 2); ring <= Math.min(radius, visual); ring++) {
            if (ring == 0) { next.add(Visibility.key(x, z)); continue; }
            for (int dx = -ring; dx <= ring; dx++) {
                next.add(Visibility.key(x + dx, z - ring)); next.add(Visibility.key(x + dx, z + ring));
            }
            for (int dz = -ring + 1; dz < ring; dz++) {
                next.add(Visibility.key(x - ring, z + dz)); next.add(Visibility.key(x + ring, z + dz));
            }
        }
        List<Long> entered = new ArrayList<>(), exited = new ArrayList<>();
        for (long key : next) if (!active.contains(key)) entered.add(key);
        for (long key : active) if (!next.contains(key)) exited.add(key);
        active = next; this.x = x; this.z = z; this.radius = radius; this.visual = visual;
        this.enabled = enabled; initialized = true;
        return new Delta(entered, exited, true);
    }
}
