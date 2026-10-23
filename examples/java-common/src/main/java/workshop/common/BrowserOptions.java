package workshop.common;

/** Operational browser settings supplied by the host framework's external configuration. */
public record BrowserOptions(boolean headless, long timeoutMillis) {
    public BrowserOptions {
        if (timeoutMillis < 1) throw new IllegalArgumentException("workshop.browser.timeout-ms must be positive");
    }
}
