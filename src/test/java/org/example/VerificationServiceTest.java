package org.example;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class VerificationServiceTest {

    private DbChunk makeChunk(String id, String text) {
        DbChunk c = new DbChunk();
        c.setChunkId(id);
        c.setText(text);
        return c;
    }

    @Test
    public void testValidOutputWithCitation() {
        // No numeric tokens in output to avoid numeric verification failures
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "Machine learning uses data to train models.")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "Machine learning uses data to train models. [source: chunkA]";
        VerificationResult vr = vs.verify(output);

        assertTrue("Expected ok=true for valid citation: " + vr.errors, vr.ok);
        assertFalse(vr.isRefusal);
        assertTrue(vr.citedChunkIds.contains("chunkA"));
    }

    @Test
    public void testMissingCitation() {
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "Some text")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "Some answer without citation.";
        VerificationResult vr = vs.verify(output);

        assertFalse("Expected ok=false when no citation", vr.ok);
        assertTrue(vr.errors.stream().anyMatch(e -> e.contains("No source citation")));
    }

    @Test
    public void testCitedIdNotInEvidence() {
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "Actual evidence text")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "Some claim [source: chunkZ]";
        VerificationResult vr = vs.verify(output);

        assertFalse("Expected ok=false for unknown chunk id", vr.ok);
        assertTrue(vr.errors.stream().anyMatch(e -> e.contains("not present in evidence")));
    }

    @Test
    public void testNormalizedIdMatching() {
        // Evidence has mixed case id, output has lowercase - should match after normalization
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("ChunkABC", "The value is here.")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "The value is here. [source: chunkabc]";
        VerificationResult vr = vs.verify(output);

        assertTrue("Should match case-insensitively: " + vr.errors, vr.ok);
    }

    @Test
    public void testCalcCorrect() {
        // Evidence must contain the numbers 10, 5, and 15 for numeric check to pass
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "There are 10 apples and 5 oranges, total 15 fruits.")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "Total is 15 fruits. [calc: 10+5=15] [source: chunkA]";
        VerificationResult vr = vs.verify(output);

        assertTrue("Calc should be correct: " + vr.errors, vr.ok);
    }

    @Test
    public void testCalcWrong() {
        // Evidence has the numbers, but calc result is wrong
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "There are 10 apples and 5 oranges, plus 20 bananas.")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "Total is 20 bananas. [calc: 10+5=20] [source: chunkA]";
        VerificationResult vr = vs.verify(output);

        assertFalse("Calc mismatch should fail", vr.ok);
        assertTrue(vr.errors.stream().anyMatch(e -> e.contains("Calc mismatch")));
    }

    @Test
    public void testExactRefusal() {
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "Some text")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "I don't have that information in your database.";
        VerificationResult vr = vs.verify(output);

        assertTrue("Exact refusal should be ok", vr.ok);
        assertTrue("Should be marked as refusal", vr.isRefusal);
    }

    @Test
    public void testNumericClaimNotInEvidence() {
        List<DbChunk> evidence = Arrays.asList(
            makeChunk("chunkA", "The project started in January.")
        );
        VerificationService vs = new VerificationService(evidence);

        String output = "The project has 500 users. [source: chunkA]";
        VerificationResult vr = vs.verify(output);

        assertFalse("Number 500 not in evidence should fail", vr.ok);
        assertTrue(vr.errors.stream().anyMatch(e -> e.contains("500")));
    }
}
