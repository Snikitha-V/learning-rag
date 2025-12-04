package org.example;

import java.util.*;

public class VerificationResult {
    public boolean ok;
    public List<String> errors = new ArrayList<>();
    public List<String> citedChunkIds = new ArrayList<>();
    public boolean isRefusal;

    public VerificationResult() {}
}
