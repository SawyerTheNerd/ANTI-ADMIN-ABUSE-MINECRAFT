package enzonic.antiadminabuse.core;

/**
 * Minimal JSON string writer.
 *
 * We hand-roll this instead of pulling in Gson so that {@code core} keeps a zero
 * dependency footprint and works on Java 8. Correct escaping matters more here
 * than it might look: command text is attacker-influenced (any player can type
 * a command containing a quote) and the original implementation concatenated it
 * straight into a payload, which produced invalid JSON and let a crafted command
 * forge extra fields in the Discord request.
 */
public final class Json {
    private Json() {}

    /** Escapes {@code raw} for use inside a JSON string literal (quotes not included). */
    public static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    // Control characters must be escaped; U+2028/U+2029 are legal
                    // JSON but break a number of lenient parsers.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** Wraps {@code raw} in quotes, escaping the contents. */
    public static String quote(String raw) {
        return "\"" + escape(raw) + "\"";
    }
}
