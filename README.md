# minecraft-i2p

A universal, cross-platform Java wrapper that connects Minecraft Java Edition
to a hidden server over the I2P network — with zero manual I2P setup.

The wrapper boots an embedded I2P router, creates the appropriate tunnel
(client or server), waits for the network to warm up, and then tells the user
how to connect. The Minecraft client and server are launched manually by the
user, keeping the wrapper focused on one job: **networking**.

---

## Features

- **Single JAR, no external I2P install required** — the router is embedded.
- **Two modes**: `client` (default) and `server`.
- **Auto-generated configuration** on first run.
- **Auto-persisted destination**: `--dest=` updates the config file.
- **Ephemeral destination override**: `--dest-override=` for quick tests.
- **Tunnel length fixed at 1** (lowest latency, best for gaming).
- **Tunnel quantity configurable** (throughput vs. CPU/bandwidth tradeoff).
- **Clean shutdown** via JVM shutdown hook.
- **Portable paths**: all user data lives under `~/.minecraft-i2p/`.
- **Cross-platform**: Windows, Linux, macOS (Java 17+).

---

## Requirements

- **Java 17 or newer** (JDK or JRE) to run. **JDK 24 or newer is
  recommended.**
- About `--enable-native-access=ALL-UNNAMED`, which appears in the examples
  below: it is a **no-op on JDK 17–23** and only matters on **JDK 24+**,
  where it silences a harmless "restricted method" warning emitted when
  I2P's `jbigi` library loads native code. The option has existed since
  JDK 16, so every example here runs as written on any supported JDK; on
  JDK 24+ without the flag the app still works, it just prints the warning.
- The following I2P JARs on the classpath (bundled in `lib/` or shaded into the JAR):
  - `i2p.jar`
  - `router.jar`
  - `i2ptunnel.jar`
  - `streaming.jar`
  - `mstreaming.jar`

---

## Installation

### Option A — Fat JAR (recommended)

Download or build the shaded `minecraft-i2p.jar` (contains all I2P dependencies).

### Option B — Thin JAR + `lib/`

Place `minecraft-i2p.jar` next to a `lib/` directory containing the I2P JARs listed above.

---

## Usage

### Client mode (default)

Connect to a hidden Minecraft server:

```bash
# JDK 24+ (recommended setup — silences the jbigi warning)
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=myserver.b32.i2p

# JDK 17–23 (the flag is a no-op there, but harmless to include)
java -jar minecraft-i2p.jar --dest=myserver.b32.i2p
```

The first run generates `~/.minecraft-i2p/minecraft-i2p.properties` with the
destination saved. Subsequent runs just need:

```bash
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar
```

When the console prints **READY TO PLAY**, wait for the line
**`[I2P] Tunnel is now ACTIVE`** (first runs can take a few minutes while the
router joins the network), then open Minecraft and connect to
`127.0.0.1:25565`.

### Server mode

Host a Minecraft server hidden behind I2P:

```bash
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --mode=server
```

The first run generates:
- `~/.minecraft-i2p/minecraft-i2p.properties`
- `~/.minecraft-i2p/minecraft-server.dat` — **the server's private key**

When the console prints **SERVER READY**, start your Minecraft server manually:

```bash
java -Xmx2G -jar server.jar nogui
```

Set `online-mode=false` in `server.properties` — Mojang authentication does not
work through I2P proxies.

Share the `.b32.i2p` address derived from your key file with your players —
it is printed in the **SERVER READY** block (see
[Getting the Server's `.b32.i2p` Address](#getting-the-servers-b32i2p-address)).

### Command-line options

| Option | Description |
|---|---|
| `--mode=client\|server` | Operation mode. Default: `client`. |
| `--dest=<b32.i2p>` | Target address. **Client mode only.** **Persists** to the config file. |
| `--dest-override=<b32.i2p>` | Target address. **Client mode only.** **Does not persist** — useful for testing. |
| `--config=<file>` | Use a custom `.properties` file. Relative paths resolve against the current directory (not `~/.minecraft-i2p/`). |
| `--check` | Validate the config file and exit. **No router started** — exit code 0/1, safe for scripts and CI. |
| `--print-address` | Print the server's `.b32.i2p` address (from the key file) and exit. **No router started** — raw address on stdout, script-friendly. |
| `--help`, `-h` | Show usage. |

### Examples

```bash
# First-time client setup — saves destination
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=abc123xyz.b32.i2p

# Subsequent client runs
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar

# Permanently switch to a different server
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=otherserver.b32.i2p

# Test a server without overwriting the saved destination
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest-override=test.b32.i2p

# Start server mode
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --mode=server

# Validate config without starting the router (CI / pre-flight)
java -jar minecraft-i2p.jar --check || exit 1

# Get the server address in a script (run on the server machine)
ADDR=$(java -jar minecraft-i2p.jar --print-address)
```

---

## File Layout

All user data lives under `~/.minecraft-i2p/`:

| Platform | Path |
|---|---|
| Windows | `C:\Users\<user>\.minecraft-i2p\` |
| Linux | `/home/<user>/.minecraft-i2p/` |
| macOS | `/Users/<user>/.minecraft-i2p/` |

Contents:

```
~/.minecraft-i2p/
├── minecraft-i2p.properties      # configuration (editable)
├── minecraft-server.dat          # server private key (BACKUP!)
├── logs/
│   └── minecraft-i2p.log         # wrapper log (auto-rotated, keep for support)
└── i2p_core/                     # I2P router state (regenerable)
    ├── router.config
    ├── netDb/
    ├── peers/
    └── logs/
```

### Backup

> **Back up `minecraft-i2p.properties` and `minecraft-server.dat`.**
>
> The `.dat` file defines your server's `.b32.i2p` address. If you lose it,
> you lose the address permanently.
>
> The `i2p_core/` directory is regenerable — you do **not** need to back it up.

---

## Configuration

The wrapper generates `minecraft-i2p.properties` on first run. All keys are
documented inline. Key points:

### Shared

| Key | Default | Description |
|---|---|---|
| `host` | `127.0.0.1` | Local Minecraft host. In client mode, where the wrapper listens. In server mode, where your Minecraft server listens. |
| `port` | `25565` | Local Minecraft port. |
| `i2pConfigDir` | `<user_dir>/i2p_core` | I2P router state directory. |
| `warmupTimeoutSeconds` | `300` | Max seconds to wait for I2P warmup. |
| `warmupCheckIntervalSeconds` | `10` | Seconds between warmup checks. |

### Tunnel

| Key | Default | Description |
|---|---|---|
| `tunnelName` | `Minecraft` | Friendly tunnel name (used in I2P logs; the web console is not bundled). |
| `inboundQuantity` | `2` | Number of inbound tunnels. Higher = more throughput. |
| `outboundQuantity` | `2` | Number of outbound tunnels. Higher = more throughput. |

> **Tunnel length is hardcoded to 1** and cannot be changed. This keeps latency
> as low as possible — critical for gaming.

### Client

| Key | Default | Description |
|---|---|---|
| `destination` | — | `.b32.i2p` address of the target server. |
| `sharedClient` | `true` | Reuse the tunnel for multiple simultaneous connections. |

### Server

| Key | Default | Description |
|---|---|---|
| `keyFile` | `<user_dir>/minecraft-server.dat` | Private key defining the `.b32.i2p` address. |
| `keyName` | `minecraft-server` | Friendly key name shown in I2P. |

---

## Building from Source

### Project structure

```
minecraft-i2p/
├── pom.xml
├── src/
│   ├── MinecraftI2P.java
│   └── lib/
│       └── i2ptunnel.jar          # installed into the local Maven repo (see below)
├── tests/
│   └── run-tests.sh               # regression suite (offline + --live lifecycle)
├── target/                        # Maven build output
│   └── minecraft-i2p-1.2.0.jar    # shaded fat JAR (executable)
└── dist/
    └── minecraft-i2p.jar          # renamed copy of the fat JAR for distribution
```


### Build commands

```bash
mvn install:install-file -Dfile=src/lib/i2ptunnel.jar -DgroupId=net.i2p -DartifactId=i2ptunnel -Dversion=2.13.0 -Dpackaging=jar -DgeneratePom=true
mvn clean package
```

The first command only needs to run once — it registers the local `i2ptunnel.jar`
in your Maven repository. The other I2P dependencies are resolved from Maven Central.

### Run

The shaded fat JAR is produced at `target/minecraft-i2p-1.2.0.jar`
(the version number comes from `pom.xml`):

```bash
java -jar target/minecraft-i2p-1.2.0.jar --dest=myserver.b32.i2p
java -jar target/minecraft-i2p-1.2.0.jar --mode=server
```

For daily use, copy/rename it (e.g. to `dist/minecraft-i2p.jar`) and run:

```bash
java -jar dist/minecraft-i2p.jar --dest=myserver.b32.i2p
```

### Running the tests

```bash
bash tests/run-tests.sh          # offline suite — seconds, no router started
bash tests/run-tests.sh --live   # + full lifecycle — 2-5 minutes
```

The offline suite covers argument parsing and rejection, `--check`, and
`--print-address` against crafted configs (uses `mktemp` for its data home;
no network needed). The `--live` run additionally boots a real server-mode
first run in a pristine home, verifies the printed `.b32.i2p` address,
stops the process gracefully (SIGTERM triggers the same shutdown hooks as
Ctrl+C), and checks that `--print-address` and `--check` agree with the
live run — all without requiring I2P network connectivity. Set `JAR=path`
to test a specific build; exit code is 0 only if everything passes.

---

## Getting the Server's `.b32.i2p` Address

When you run in server mode for the first time, the wrapper creates
`~/.minecraft-i2p/minecraft-server.dat`. The private key fully determines
the server's `.b32.i2p` address — **the wrapper prints it directly** in the
**SERVER READY** block, e.g.:

```
  --- Your server's .b32.i2p address ---
  zr25cum...y4ta.b32.i2p
```

Share it with your players — they use it with:

```bash
java -jar minecraft-i2p.jar --dest=<that-address>
```

On the very first server run the `.dat` key file does not exist yet — the
wrapper waits up to 30 seconds for the background tunnel to create it,
printing `(generating the key, please wait...)` inside the box meanwhile.
On subsequent runs the address appears instantly.

The address is stable as long as `minecraft-server.dat` is unchanged (it is
reprinted on every server-mode run). Note that the I2P **web console is not
available** in this fat-JAR setup (no bundled Jetty webapps) — you never need
it; everything the wrapper prints is in the terminal.

---

## Notes and Caveats

- **`online-mode=false` is required** on the Minecraft server. Mojang's
  authentication servers cannot be reached through I2P.
- **Do not run with `sudo` or as root.** The wrapper writes to `~/.minecraft-i2p/`
  and running as root would place data in `/root/`, which is almost never intended.
- **Tunnel length is intentionally fixed at 1.** More hops improve anonymity
  but add latency that ruins the gameplay experience.
- **The `TunnelController` API** may vary slightly between I2P versions. If
  compilation fails on `TunnelControllerGroup` or `TunnelController`, adjust
  the constructors to match your `i2ptunnel.jar` version.
- **First run is slow.** The I2P router needs to bootstrap into the network
  (reseed + peer discovery). This can take several minutes. Subsequent runs
  are much faster because the state is cached in `i2p_core/`.
- **Tunnels build in the background.** The wrapper registers the tunnel and
  prints `[I2P] Tunnel is now ACTIVE` when it succeeds — Minecraft
  connections fail until then. If it never appears, check
  `~/.minecraft-i2p/logs/minecraft-i2p.log`.

---

## Troubleshooting

All wrapper output is also logged to `~/.minecraft-i2p/logs/minecraft-i2p.log`
(rotated to `minecraft-i2p.log.old` past 1 MB). `--check` validates the
config without starting the router.

| Symptom | What to check |
|---|---|
| Minecraft says "can't connect" right after **READY TO PLAY** | The tunnel is still building. Connections only work after the line `[I2P] Tunnel is now ACTIVE` appears — first runs take several minutes (reseed + peer discovery). |
| `[ERROR] Tunnel build failed (state: stopped)` | The target address is offline or mistyped, or (server mode) your Minecraft server is not listening on the configured `host:port`. Run `--check` on both machines. |
| `[ERROR] Invalid destination format` | Run `java -jar minecraft-i2p.jar --check` — it shows exactly which line of the config is wrong and what format is expected. |
| Server printed `(no address yet ...)` on first run | The key file wasn't created within 30 s (slow router or unwritable data dir). Run `--mode=server` again in a few minutes; `--print-address` works as soon as `minecraft-server.dat` exists. |
| Players can't reach your server although it worked before | The `.dat` key was recreated — an old `minecraft-server.dat` was deleted or `keyFile` points elsewhere. The address follows the key; restore your backup. |
| "Address already in use" at startup | Another program (or a local Minecraft server) holds the configured `port`. Change `port` in the config. |
| Process dies instantly without any `[SHUTDOWN]` lines on Ctrl+C | The embedded router's kill-VM-on-end behavior (`setKillVMOnEnd`) changed in a future I2P build — the wrapper tries to disable it; if you see this, report it with the I2P version from `pom.xml`. |
| Minecraft logins rejected / "Session cannot be verified" | `online-mode=false` is required in `server.properties` — Mojang auth is unreachable through I2P. |

---

## Dependencies

Requires **JDK 17 or newer** and **Maven**. Example installation on Debian/Ubuntu:

```sh
# Debian 12 (Bookworm)
sudo apt install openjdk-17-jdk maven

# Debian 13 (Trixie) or newer — JDK 17 is gone; use the default
sudo apt install default-jdk maven

# Verify
java -version    # must report 17 or newer
mvn -version
```

> On newer Debian/Ubuntu releases the OpenJDK 17 package may have been
> removed in favor of newer JDKs. `default-jdk` always installs the current
> supported default (JDK 21 on Debian 13). JDK 21 runs everything fine —
> the `--enable-native-access` flag is simply a no-op there (see
> [Requirements](#requirements)).

If you have multiple JDKs installed, select the default with:

```sh
sudo update-alternatives --config java
```

---

## Acknowledgements

- The I2P project for the embedded router and tunnel APIs.
- The Minecraft community for protocol documentation.
