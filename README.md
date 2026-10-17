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

- **Java 17 or newer** (JDK or JRE).
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
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=myserver.b32.i2p
```

The first run generates `~/.minecraft-i2p/minecraft-i2p.properties` with the
destination saved. Subsequent runs just need:

```bash
java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar
```

When the console prints **READY TO PLAY**, open Minecraft and connect to
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

Share the `.b32.i2p` address derived from your key file with your players.

### Command-line options

| Option | Description |
|---|---|
| `--mode=client\|server` | Operation mode. Default: `client`. |
| `--dest=<b32.i2p>` | Client destination. **Persists** to the config file. |
| `--dest-override=<b32.i2p>` | Client destination. **Does not persist** — useful for testing. |
| `--config=<file>` | Use a custom `.properties` file. |
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
| `tunnelName` | `Minecraft` | Friendly name shown in the I2P console. |
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
├── target/                        # Maven build output
│   └── minecraft-i2p-1.0.1.jar    # shaded fat JAR (executable)
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

The shaded fat JAR is produced at `target/minecraft-i2p-1.0.1.jar`
(the version number comes from `pom.xml`):

```bash
java -jar target/minecraft-i2p-1.0.1.jar --dest=myserver.b32.i2p
java -jar target/minecraft-i2p-1.0.1.jar --mode=server
```

For daily use, copy/rename it (e.g. to `dist/minecraft-i2p.jar`) and run:

```bash
java -jar dist/minecraft-i2p.jar --dest=myserver.b32.i2p
```

---

## Getting the Server's `.b32.i2p` Address

When you run in server mode for the first time, the wrapper creates
`~/.minecraft-i2p/minecraft-server.dat`. The I2P router will announce a
`.b32.i2p` address derived from this key.

To find the address, look for it in the I2P router console or the wrapper's
logs. Share it with your players — they will use it with:

```bash
java -jar minecraft-i2p.jar --dest=<that-address>.b32.i2p
```

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

---

## Dependencies

Requires **JDK 17 or newer** and **Maven**. Example installation on Debian/Ubuntu:

```sh
sudo apt install openjdk-25-jdk maven
java -version    # must report 25 or newer
mvn -version
```

If you have multiple JDKs installed, select the default with:

```sh
sudo update-alternatives --config java
```

---

## Acknowledgements

- The I2P project for the embedded router and tunnel APIs.
- The Minecraft community for protocol documentation.
