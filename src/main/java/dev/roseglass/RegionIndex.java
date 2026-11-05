package dev.roseglass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Worker-owned spatial directory index; region grouping does not change disk layout. */
final class RegionIndex {
    static final class Column {
        final long key;
        final int x, z;
        final Path path;
        Column(int x, int z, Path path) { this.x = x; this.z = z; this.path = path; key = Visibility.key(x, z); }
    }
    private final NavigableMap<Integer, NavigableMap<Integer, Map<Long, Column>>> regions = new TreeMap<>();
    int lastExamined;

    void put(int x, int z, Path path) {
        regions.computeIfAbsent(x >> 5, k -> new TreeMap<>())
                .computeIfAbsent(z >> 5, k -> new HashMap<>()).put(Visibility.key(x, z), new Column(x, z, path));
    }

    void remove(int x, int z) {
        NavigableMap<Integer, Map<Long, Column>> row = regions.get(x >> 5);
        if (row == null) return;
        Map<Long, Column> region = row.get(z >> 5);
        if (region == null) return;
        region.remove(Visibility.key(x, z));
        if (region.isEmpty()) row.remove(z >> 5);
        if (row.isEmpty()) regions.remove(x >> 5);
    }

    List<Column> nearby(int x, int z, int radius) {
        int minX = region((long) x - radius), maxX = region((long) x + radius);
        int minZ = region((long) z - radius), maxZ = region((long) z + radius);
        List<Column> result = new ArrayList<>();
        lastExamined = 0;
        for (NavigableMap<Integer, Map<Long, Column>> row : regions.subMap(minX, true, maxX, true).values()) {
            for (Map<Long, Column> region : row.subMap(minZ, true, maxZ, true).values()) {
                for (Column column : region.values()) {
                    lastExamined++;
                    if (Visibility.distance(x, z, column.x, column.z) <= radius) result.add(column);
                }
            }
        }
        result.sort(Comparator.comparingLong((Column c) -> Visibility.distance(x, z, c.x, c.z))
                .thenComparingLong(c -> c.key));
        return result;
    }

    private static int region(long coordinate) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, coordinate)) >> 5;
    }
}
