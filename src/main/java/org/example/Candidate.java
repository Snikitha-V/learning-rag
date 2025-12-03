package org.example;

import java.util.Map;

public class Candidate {
    public String id;
    public double score;
    public Map<String, Object> payload;
    public float[] vector;

    @Override
    public String toString() {
        return "Candidate{id='" + id + "', score=" + score + ", vector=" + (vector==null? "null":"len="+vector.length) + "}";
    }
}
