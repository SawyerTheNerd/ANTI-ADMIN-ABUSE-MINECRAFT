package enzonic.antiadminabuse.core;

/** One observed command execution, already normalised by the platform layer. */
public final class CommandRecord {

    public enum Source { PLAYER, CONSOLE, RCON, PROXY }

    public final String senderName;
    /** Player UUID as a string, or null for console/unknown senders. */
    public final String senderId;
    public final Source source;
    /** Raw command text, with or without a leading slash. */
    public final String command;
    /** World / backend server the sender was on, or null when not applicable. */
    public final String location;
    public final boolean privileged;
    public final long timestampMillis;

    public CommandRecord(String senderName, String senderId, Source source, String command,
                         String location, boolean privileged, long timestampMillis) {
        this.senderName = senderName == null ? "unknown" : senderName;
        this.senderId = senderId;
        this.source = source == null ? Source.PLAYER : source;
        this.command = command == null ? "" : command;
        this.location = location;
        this.privileged = privileged;
        this.timestampMillis = timestampMillis;
    }

    public String commandName() {
        return AbuseConfig.commandName(command);
    }
}
