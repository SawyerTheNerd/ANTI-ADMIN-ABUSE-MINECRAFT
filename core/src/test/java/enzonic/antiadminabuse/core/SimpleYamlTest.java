package enzonic.antiadminabuse.core;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the config reader used on Velocity, Fabric and NeoForge, none of which
 * ship a YAML parser we can lean on.
 */
public class SimpleYamlTest {

    private static SimpleYaml of(String... lines) {
        return SimpleYaml.parse(Arrays.asList(lines));
    }

    @Test
    public void readsScalarsAndStripsQuotes() {
        SimpleYaml y = of("enabled: true",
                          "webhook-url: \"https://discord.com/api/webhooks/1/abc\"",
                          "discord-username: 'Anti Admin Abuse'",
                          "max-retries: 3");
        assertTrue(y.bool("enabled", false));
        assertEquals("https://discord.com/api/webhooks/1/abc", y.string("webhook-url", ""));
        assertEquals("Anti Admin Abuse", y.string("discord-username", ""));
        assertEquals(3, y.integer("max-retries", 0));
    }

    @Test
    public void ignoresCommentsAndBlankLines() {
        SimpleYaml y = of("# a comment", "", "   ",
                          "enabled: false   # trailing comment",
                          "# server-name: commented-out");
        assertFalse(y.bool("enabled", true));
        assertEquals("fallback", y.string("server-name", "fallback"));
    }

    @Test
    public void hashInsideQuotesIsNotAComment() {
        assertEquals("survival #1", of("server-name: \"survival #1\"").string("server-name", ""));
    }

    @Test
    public void readsInlineLists() {
        SimpleYaml y = of("watched-commands: [ op, deop, gamemode ]", "ignored-commands: []");
        assertEquals(Arrays.asList("op", "deop", "gamemode"), y.list("watched-commands"));
        assertTrue(y.hasList("ignored-commands"));
        assertTrue(y.list("ignored-commands").isEmpty());
    }

    @Test
    public void readsBlockListsWithoutBleedingIntoTheNextKey() {
        SimpleYaml y = of("redacted-commands:",
                          "  - login",
                          "  - register",
                          "max-retries: 2",
                          "ignored-commands:",
                          "  - msg");
        assertEquals(Arrays.asList("login", "register"), y.list("redacted-commands"));
        assertEquals(2, y.integer("max-retries", 0));
        assertEquals(Arrays.asList("msg"), y.list("ignored-commands"));
    }

    @Test
    public void badIntegerFallsBackInsteadOfThrowing() {
        assertEquals(7, of("max-retries: banana").integer("max-retries", 7));
    }

    @Test
    public void malformedLinesAreIgnored() {
        SimpleYaml y = of("this line has no colon", "enabled: true", "]]]");
        assertTrue(y.bool("enabled", false));
    }

    /**
     * The shipped default config is the one document that must parse correctly,
     * since every untouched default a user runs with comes from it.
     */
    @Test
    public void parsesTheShippedDefaultConfig() throws Exception {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("default-config.yml");
        assertTrue("default-config.yml missing from test resources", in != null);
        SimpleYaml y = SimpleYaml.read(in);

        assertEquals("YOUR_DISCORD_WEBHOOK_URL_HERE", y.string("webhook-url", ""));
        assertTrue(y.bool("enabled", false));
        assertTrue(y.bool("use-embed", false));
        assertEquals(2000, y.integer("queue-capacity", 0));
        assertTrue(y.list("watched-commands").isEmpty());
        assertTrue(y.list("ignored-commands").contains("msg"));
        assertTrue(y.list("redacted-commands").contains("login"));
        // The commented-out example must not leak into real config.
        assertFalse(y.list("watched-commands").contains("op"));

        AbuseConfig cfg = y.toConfig(new AbuseConfig());
        assertFalse("placeholder URL must not count as configured", cfg.hasUsableWebhook());
        assertTrue(cfg.redactedCommands.contains("login"));
    }
}
