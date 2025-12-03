package org.example;

import java.util.*;

public class MMR {
    private static double cosine(float[] a, float[] b) {
        if (a==null || b==null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i]*a[i];
            nb += b[i]*b[i];
        }
        if (na==0 || nb==0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static List<Candidate> rerank(List<Candidate> candidates, float[] queryVector, int k, double lambda) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        int n = candidates.size();
        boolean[] selected = new boolean[n];

        double[] simQuery = new double[n];
        for (int i = 0; i < n; i++) simQuery[i] = cosine(queryVector, candidates.get(i).vector);

        List<Candidate> result = new ArrayList<>();
        int first = 0;
        for (int i = 1; i < n; i++) if (simQuery[i] > simQuery[first]) first = i;
        result.add(candidates.get(first));
        selected[first] = true;

        while (result.size() < Math.min(k, n)) {
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            for (int i = 0; i < n; i++) {
                if (selected[i]) continue;
                double maxSimWithSelected = Double.NEGATIVE_INFINITY;
                for (Candidate s : result) {
                    double sim = cosine(candidates.get(i).vector, s.vector);
                    if (sim > maxSimWithSelected) maxSimWithSelected = sim;
                }
                if (maxSimWithSelected == Double.NEGATIVE_INFINITY) maxSimWithSelected = 0;
                double score = lambda * simQuery[i] - (1 - lambda) * maxSimWithSelected;
                if (score > bestScore) { bestScore = score; bestIdx = i; }
            }
            if (bestIdx == -1) break;
            selected[bestIdx] = true;
            result.add(candidates.get(bestIdx));
        }
        return result;
    }
}

