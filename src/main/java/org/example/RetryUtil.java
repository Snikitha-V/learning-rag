package org.example;

import java.util.concurrent.Callable;

/**
 * Simple retry utility for transient failures.
 */
public class RetryUtil {

    /**
     * Retry a callable up to `attempts` times with exponential backoff.
     */
    public static <T> T withRetry(Callable<T> c, int attempts) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return c.call();
            } catch (Exception e) {
                attempt++;
                if (attempt >= attempts) throw e;
                Thread.sleep(200L * attempt);
            }
        }
    }
}
