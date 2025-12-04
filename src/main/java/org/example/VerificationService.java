package org.example;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verifies model output against evidence chunks.
 */
public class VerificationService {
    private static final Pattern sourcePattern = Pattern.compile("\\[source:\\s*([A-Za-z0-9_\\-:, ]+)\\]");
    private static final Pattern calcPattern = Pattern.compile("\\[calc:([^\\]]+)\\]");
    private static final Pattern datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}"); // simple ISO date detector

    private final Set<String> evidenceChunkIds;
    private final Map<String, String> chunkTextMap; // id -> text (lowercase for search)

    public VerificationService(List<DbChunk> evidence) {
        // Normalize chunk IDs (trim + lowercase) for tolerant matching
        this.evidenceChunkIds = evidence.stream()
            .map(c -> c.getChunkId() == null ? "" : c.getChunkId().trim().toLowerCase())
            .collect(Collectors.toSet());
        this.chunkTextMap = new HashMap<>();
        for (DbChunk c : evidence) {
            String id = c.getChunkId() == null ? "" : c.getChunkId().trim().toLowerCase();
            String t = c.getText() == null ? "" : c.getText();
            chunkTextMap.put(id, t.toLowerCase());
        }
    }

    public VerificationResult verify(String modelOutput) {
        VerificationResult res = new VerificationResult();
        res.ok = true; // assume valid until proven otherwise
        if (modelOutput == null) {
            res.ok = false;
            res.errors.add("No output from model");
            return res;
        }
        String out = modelOutput.trim();

        // Check refusal exact match
        if (out.equals("I don't have that information in your database.")) {
            res.ok = true;
            res.isRefusal = true;
            return res;
        }

        // Extract cited chunk ids
        Matcher m = sourcePattern.matcher(out);
        Set<String> cited = new LinkedHashSet<>();
        while (m.find()) {
            String group = m.group(1);
            // allow comma separated
            String[] parts = group.split("[,]");
            for (String p : parts) {
                String id = p.trim();
                if (!id.isEmpty()) cited.add(id);
            }
        }
        res.citedChunkIds.addAll(cited);

        if (cited.isEmpty()) {
            res.ok = false;
            res.errors.add("No source citation found in output. Every factual sentence must end with [source: CHUNK_ID].");
            return res;
        }

        // Ensure all cited ids are within evidence (normalize for comparison)
        for (String id : cited) {
            String normalizedId = id.trim().toLowerCase();
            if (!evidenceChunkIds.contains(normalizedId)) {
                res.ok = false;
                res.errors.add("Cited chunk id not present in evidence: " + id);
            }
        }
        if (!res.errors.isEmpty()) return res;

        // Numeric/date claims verification: find numbers and dates in output and ensure they appear in at least one cited chunk
        // Simple heuristic: for each number or ISO date token, check presence in any cited chunk text
        List<String> tokensToCheck = new ArrayList<>();
        // numbers
        Matcher numM = Pattern.compile("\\b\\d+\\b").matcher(out);
        while (numM.find()) tokensToCheck.add(numM.group());
        // dates
        Matcher dateM = datePattern.matcher(out);
        while (dateM.find()) tokensToCheck.add(dateM.group());

        for (String token : tokensToCheck) {
            boolean found = false;
            for (String cid : cited) {
                String normalizedCid = cid.trim().toLowerCase();
                String txt = chunkTextMap.getOrDefault(normalizedCid, "");
                if (txt.contains(token.toLowerCase())) { found = true; break; }
            }
            if (!found) {
                res.ok = false;
                res.errors.add("Claim token '" + token + "' not found in cited chunks.");
            }
        }
        if (!res.ok) return res;

        // Calc checks: parse any [calc: expr = value] pattern and verify arithmetic
        Matcher calcM = calcPattern.matcher(out);
        while (calcM.find()) {
            String expr = calcM.group(1).trim(); // e.g. "2+3=5" or "2 + 3 = 5"
            // simple parsing: split by '='
            String[] sides = expr.split("=");
            if (sides.length != 2) { res.ok = false; res.errors.add("Invalid calc format: " + expr); break; }
            String left = sides[0].trim();
            String right = sides[1].trim();
            try {
                // evaluate left (support simple + - * /)
                double leftVal = evalSimpleArithmetic(left);
                double rightVal = Double.parseDouble(right);
                if (Math.abs(leftVal - rightVal) > 1e-6) {
                    res.ok = false;
                    res.errors.add("Calc mismatch: " + expr + " evaluated to " + leftVal + " but expected " + rightVal);
                    break;
                }
            } catch (Exception e) {
                res.ok = false;
                res.errors.add("Calc parse error: " + expr + " -> " + e.getMessage());
                break;
            }
        }
        if (!res.ok) return res;

        // If passed all checks
        res.ok = true;
        return res;
    }

    // very small arithmetic evaluator: supports + - * / and parentheses
    private double evalSimpleArithmetic(String expr) {
        // remove spaces
        String s = expr.replaceAll("\\s+", "");
        return evalExpr(s);
    }

    // Recursive descent parser - supports + - * / and parentheses
    private double evalExpr(String s) {
        return new ExprParser(s).parse();
    }

    // small expression parser inner class
    private static class ExprParser {
        private final String s;
        private int pos = 0;
        ExprParser(String s) { this.s = s; }
        double parse() {
            double v = parseAddSub();
            if (pos != s.length()) throw new IllegalArgumentException("Unexpected: " + s.substring(pos));
            return v;
        }
        double parseAddSub() {
            double v = parseMulDiv();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += parseMulDiv(); }
                else if (c == '-') { pos++; v -= parseMulDiv(); }
                else break;
            }
            return v;
        }
        double parseMulDiv() {
            double v = parseUnary();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= parseUnary(); }
                else if (c == '/') { pos++; v /= parseUnary(); }
                else break;
            }
            return v;
        }
        double parseUnary() {
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return parseUnary(); }
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -parseUnary(); }
            return parsePrimary();
        }
        double parsePrimary() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++; double v = parseAddSub();
                if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("Missing )");
                pos++; return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos)=='.')) pos++;
            if (start == pos) throw new IllegalArgumentException("Number expected at " + pos);
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
