package org.example;

import java.util.*;

public class LruCache<K,V> extends LinkedHashMap<K,V> {
    private final int maxEntries;
    public LruCache(int maxEntries) {
        super(maxEntries + 1, 0.75f, true);
        this.maxEntries = maxEntries;
    }
    @Override
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size() > maxEntries;
    }
}

