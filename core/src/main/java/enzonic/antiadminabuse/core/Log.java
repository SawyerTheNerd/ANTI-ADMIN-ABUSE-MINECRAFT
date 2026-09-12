package enzonic.antiadminabuse.core;

/**
 * Tiny logging seam so {@code core} never touches a platform logger directly.
 * Each platform adapts its own logger onto this.
 */
public interface Log {
    void info(String message);
    void warn(String message);
    void warn(String message, Throwable error);

    /** Discards everything; handy in tests. */
    Log NOOP = new Log() {
        public void info(String message) {}
        public void warn(String message) {}
        public void warn(String message, Throwable error) {}
    };
}
