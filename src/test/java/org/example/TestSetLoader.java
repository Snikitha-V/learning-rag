package org.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class TestSetLoader {
    // CSV format for factual: id,query,expected_exact_answer
    // CSV format for semantic: id,query,expected_chunk_ids(comma separated)
    public static List<TestCase> loadFactual(Path csv) throws IOException {
        return Files.lines(csv).skip(1).map(line -> {
            String[] p = line.split(",",3);
            return TestCase.factual(p[0].trim(), p[1].trim(), p[2].trim());
        }).collect(Collectors.toList());
    }
    public static List<TestCase> loadSemantic(Path csv) throws IOException {
        return Files.lines(csv).skip(1).map(line -> {
            String[] p = line.split(",",3);
            List<String> ids = Arrays.stream(p[2].split(";")).map(String::trim).filter(s->!s.isEmpty()).collect(Collectors.toList());
            return TestCase.semantic(p[0].trim(), p[1].trim(), ids);
        }).collect(Collectors.toList());
    }
    public static List<TestCase> loadMixed(Path csv) throws IOException {
        return Files.lines(csv).skip(1).map(line -> {
            String[] p = line.split(",",4);
            List<String> ids = Arrays.stream(p[3].split(";")).map(String::trim).collect(Collectors.toList());
            return TestCase.mixed(p[0].trim(), p[1].trim(), p[2].trim(), ids);
        }).collect(Collectors.toList());
    }
}
