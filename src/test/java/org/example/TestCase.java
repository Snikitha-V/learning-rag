package org.example;

import java.util.*;

public class TestCase {
    public enum Type { FACTUAL, SEMANTIC, MIXED }

    public String id;           // e.g., F001
    public Type type;
    public String query;
    public String expected;     // for factual: expected exact SQL string; for semantic/mixed: expected keywords or list of chunk ids (comma-separated)
    public List<String> expectedChunkIds; // optional: for semantic/mixed verification
    
    public static TestCase factual(String id, String q, String expected) {
        TestCase t = new TestCase(); t.id=id; t.type=Type.FACTUAL; t.query=q; t.expected=expected; t.expectedChunkIds = new ArrayList<>(); return t;
    }
    public static TestCase semantic(String id, String q, List<String> chunkIds) {
        TestCase t = new TestCase(); t.id=id; t.type=Type.SEMANTIC; t.query=q; t.expected=""; t.expectedChunkIds = chunkIds; return t;
    }
    public static TestCase mixed(String id, String q, String expectedFact, List<String> chunkIds) {
        TestCase t = new TestCase(); t.id=id; t.type=Type.MIXED; t.query=q; t.expected=expectedFact; t.expectedChunkIds = chunkIds; return t;
    }
}

