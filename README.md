# ANTI ADMIN ABUSE

Logs every command run on your server to a Discord webhook, so staff actions leave
an audit trail that somebody outside the game can read.

- **Modrinth:** https://modrinth.com/plugin/anti-admin-abuse
- **Source:** https://github.com/SawyerTheNerd/ANTI-ADMIN-ABUSE-MINECRAFT

## What you download

Pick the one file that matches your server. They are separate artifacts because
the platforms genuinely differ, not because the features do — all of them share
the same filtering, redaction and delivery code.

| File | Server software | Minecraft versions | Java |
|---|---|---|---|
| `AntiAdminAbuse-Bukkit-*.jar` | Bukkit, Spigot, Paper, Purpur, Pufferfish, Folia | **1.8 → current** | 8+ |
| `AntiAdminAbuse-Bungee-*.jar` | BungeeCord, Waterfall | any | 8+ |
| `AntiAdminAbuse-Velocity-*.jar` | Velocity 3 | any | 17+ |
| `AntiAdminAbuse-Fabric-*+<mc>.jar` | Fabric | one build per MC version | 21+ |
| `AntiAdminAbuse-NeoForge-*+<mc>.jar` | NeoForge | one build per MC version | 21+ |

### Why the Bukkit build is one jar but Fabric and NeoForge are not

The Bukkit plugin only uses API that has been stable since 1.8 (`JavaPlugin`,
`PlayerCommandPreprocessEvent`, `ServerCommandEvent`), it is compiled to Java 8
bytecode, and it declares `api-version: 1.13` — the oldest value that modern
servers accept, which older servers ignore. One file therefore loads everywhere.

Fabric and NeoForge mods are compiled against *remapped Minecraft classes*, so a
mod built for 1.21.11 cannot load on 1.20. Each Minecraft version needs its own
build; see [Building](#building).

## Setup

1. Drop the jar in `plugins/` (Bukkit/proxy) or `mods/` (Fabric/NeoForge) and
   start the server once to generate the config.
2. Create a webhook in Discord: **Server Settings → Integrations → Webhooks →
   New Webhook → Copy URL**.
3. Set it:
   - **Bukkit:** `/aaa setwebhook <url>` in game or console — this keeps the URL
     out of your shell history and out of screenshots.
   - **Everything else:** edit `webhook-url` in the config file, then restart.
4. Check it works: `/aaa test` (Bukkit), or just run any command.

> The webhook URL is a credential. Anyone who has it can post in that channel, so
> treat it like a password — the plugin never echoes it back into chat, and `/aaa
> setwebhook` is redacted out of its own logs.

### Commands (Bukkit only)

| Command | What it does |
|---|---|
| `/aaa setwebhook <url>` | Save the webhook URL and apply it immediately |
| `/aaa reload` | Re-read `config.yml` without restarting |
| `/aaa status` | Show settings plus delivered / failed / dropped / queued counters |
| `/aaa test` | Send a test message to Discord |

`/antiadminabuse` and `/antiabuse` are aliases; `/setwebhook` still works for
setups that documented it. Permission: `antiadminabuse.admin` (default: op).

The proxy and mod builds have no commands — a proxy or mod has no equivalent of
Bukkit's permission-checked command registration that is worth the surface area,
so they are configured by file.

## Configuration

The same keys mean the same thing on every platform.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch; `false` stops all reporting |
| `webhook-url` | *(placeholder)* | Your Discord webhook |
| `discord-username` | `Anti Admin Abuse` | Message author shown in Discord |
| `server-name` | *(empty)* | Label added to each message, so one channel can serve several servers |
| `use-embed` | `true` | Rich embed vs. a single plain line |
| `log-player-commands` | `true` | Report commands typed by players |
| `log-console-commands` | `true` | Report console and RCON commands |
| `only-staff-commands` | `false` | Report only operators and holders of `antiadminabuse.staff`. Console/RCON are always reported, being inherently privileged |
| `watched-commands` | `[]` | **Allow-list.** If non-empty, *only* these are reported and the ignore list is skipped |
| `ignored-commands` | chat/info commands | Never reported |
| `redacted-commands` | login/auth commands | Arguments replaced with `***` |
| `connect-timeout-millis` | `5000` | Connect timeout |
| `read-timeout-millis` | `8000` | Read timeout |
| `max-retries` | `3` | Retries after a rate limit or server error |
| `queue-capacity` | `2000` | Pending messages held if Discord is unreachable |

Command names are matched case-insensitively, with any plugin prefix stripped, so
`op`, `/OP` and `minecraft:op` are all the same entry.

### Redaction is on by default, and you should keep it that way

A logger that faithfully forwards `/login hunter2` to Discord turns an
accountability tool into a credential leak. `redacted-commands` ships with the
common auth commands (`login`, `register`, `changepassword`, `authme`, `2fa`, …)
plus `op` and `setwebhook`. Add anything on your server that takes a secret.

## Verified compatibility

Every row below was booted for real: the server starts, the plugin loads, three
commands are driven through the console, and the harness asserts on the HTTP
payloads that actually arrive — that `say` is reported, that `op <user>` arrives
as `/op ***` with the username absent, and that an ignored command produces
nothing at all.

| Platform | Versions verified | What was checked |
|---|---|---|
| Paper | 1.8.8, 1.12.2, 1.16.5, 1.17.1, 1.18.2, 1.20.1, 1.20.6, 1.21.4, 1.21.11, 26.1.2, 26.2 | full — delivery, redaction, filtering |
| Folia | 1.21.11, 26.2 | full |
| Purpur | 1.21.11, 26.2 | full |
| Velocity | 3.5.1 | full — console commands are reported |
| Fabric | 1.21.11 | full |
| NeoForge | 1.21.11, 26.2 | full |
| BungeeCord | latest build | **loads and enables only** — see below |

All of the above pass. The Paper rows span four different JVM eras (Java 8, 17,
21 and 25), chosen because adjacent patch releases share the same API and Java
requirement; testing every one of the 100+ releases would not find anything these
do not.

Spigot and CraftBukkit are absent because neither publishes a prebuilt server
jar (they require BuildTools). They expose a strict subset of the Paper API used
here, which is exactly why the Bukkit module is compiled against the **1.8.8
Spigot API** and nothing newer — a newer symbol would fail to compile rather than
fail at runtime on someone's server.

### BungeeCord is load-verified, not function-verified

BungeeCord exposes no console command event, and the listener hooks `ChatEvent`,
which only a real connected player can trigger. The automated test therefore
confirms the plugin loads and enables cleanly but cannot exercise the logging
path end to end. The logic it runs is the same `core` code verified on every
other platform, but the BungeeCord-specific hook itself is untested — if you rely
on it, confirm with a real player before trusting it.

### Fabric and NeoForge version coverage

These builds are offered for the versions in the table, not for everything.

- **Fabric on 26.x is not available.** Yarn mappings have not been published for
  any 26.x release, and Loom additionally requires Gradle itself to run on Java
  25 for those versions. Use the Bukkit build, which does support 26.2.
- **Older Minecraft on either loader needs source changes,** not just a rebuild.
  Mojang moved from numeric permission levels to named permissions and renamed
  `ResourceKey.location()` to `identifier()`, so the privilege check and world
  lookup differ by version. The command hook already tolerates the rename from
  `executeWithPrefix` to `parseAndExecute`.

Minecraft's move to calendar versioning (26.1, 26.2) raised the JVM floor to
**Java 25**. The plugin is unaffected: Java 8 bytecode loads on every one of
these JVMs.

### Folia

Supported, and marked `folia-supported: true`. The plugin never touches a
scheduler: HTTP delivery runs on a thread the plugin owns, so there is no main
thread to be wrong about. That is also why delivery never blocks gameplay.

## Building

```bash
./gradlew build                 # core, Bukkit, BungeeCord, Velocity + tests
./gradlew :bukkit:jar           # the one jar for every Bukkit-family server
```

Mod loaders target one Minecraft version per build:

```bash
./gradlew :fabric:build   -PfabricMcVersion=1.21.11 -PyarnVersion=1.21.11+build.6 \
                          -PfabricApiVersion=0.141.6+1.21.11
./gradlew :neoforge:build -PneoMcVersion=1.21.11 -PneoVersion=21.11.45
```

Gradle provisions the JDKs it needs (21 for 1.21.x, 25 for 26.x) via the foojay
resolver, so only a Java 17+ JDK to run Gradle itself is required.

### Layout

```
core/       all decision logic; Java 8, zero dependencies, unit-tested
bukkit/     Bukkit/Spigot/Paper/Purpur/Folia  (compiled against the 1.8.8 API)
bungee/     BungeeCord / Waterfall
velocity/   Velocity 3
fabric/     Fabric mod (mixin on command dispatch)
neoforge/   NeoForge mod (CommandEvent)
```

`core` has no server API on its classpath at all, which is what guarantees a
platform module cannot leak a version-specific symbol into shared logic. Each
platform module does three things: build a `CommandRecord`, hand it to
`AbuseService`, and forward config changes.

## Running the tests

```bash
./gradlew :core:test
```

These cover JSON escaping against crafted commands, redaction, the filtering
rules, the config parser, and real HTTP delivery against a throwaway local
listener — including that a full queue drops instead of blocking, that a 429 is
retried while a 404 is not, and that queued entries flush on shutdown.

## Notes for upgraders from 1.0

- `config.yml` gained the keys in the table above; existing files keep working and
  pick up defaults for anything missing.
- `/setwebhook` still works. `/aaa` is the new front end.
- Delivery moved to a background thread. Previously every command executed
  blocked the main thread for a full HTTP round trip to Discord, so a slow or
  unreachable webhook meant a multi-second freeze per command.
- Command text is now JSON-escaped. Previously a command containing a quote
  produced an invalid payload that Discord rejected.
