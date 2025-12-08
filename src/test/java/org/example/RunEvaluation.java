package org.example;

import java.nio.file.*;
import java.util.*;

public class RunEvaluation {
    public static void main(String[] args) throws Exception {
        RetrievalService svc = RetrievalService.createDefault();
        SqlService sql = new SqlService(Config.DB_URL, Config.DB_USER, Config.DB_PASS);
        QdrantClient qc = new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
        Evaluator ev = new Evaluator(svc, sql, qc);

        List<TestCase> all = new ArrayList<>();
        all.addAll(TestSetLoader.loadFactual(Paths.get("src/test/resources/factual.csv")));
        all.addAll(TestSetLoader.loadSemantic(Paths.get("src/test/resources/semantic.csv")));
        all.addAll(TestSetLoader.loadMixed(Paths.get("src/test/resources/mixed.csv")));

        List<Evaluator.Result> results = new ArrayList<>();
        for (TestCase tc : all) {
            System.out.println("Running " + tc.id + " ...");
            Evaluator.Result r = ev.run(tc);
            results.add(r);
            System.out.println("-> " + r.rawAnswer);
        }
        Path out = Paths.get("src/test/resources/eval_results.csv");
        ev.saveResults(results, out);
        System.out.println("Saved results to " + out.toAbsolutePath());
        System.out.println("Aggregates: " + Evaluator.aggregate(results));
    }
}
