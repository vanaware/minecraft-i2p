import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.router.Router;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * minecraft-i2p - Universal I2P wrapper for Minecraft
 *
 * Usage:
 *   java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar
 *   java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=X.b32.i2p
 *   java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest-override=X.b32.i2p
 *   java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --mode=server
 *   java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --config=custom.properties
 *
 * The --enable-native-access flag only matters on JDK 24+ (it silences a
 * harmless jbigi warning); omit it on JDK 17-23.
 *
 * Default files (all under ~/.minecraft-i2p/):
 *   minecraft-i2p.properties   - editable configuration
 *   minecraft-server.dat       - server private key (backup!)
 *   i2p_core/                  - I2P router state (regenerable)
 *
 * On first run, the properties file is generated automatically.
 * Tunnel length is hardcoded to 1 (lowest latency). Quantity is configurable.
 */
public class MinecraftI2P {

    // ==========================================================
    // DIRECTORIES AND CONSTANTS
    // ==========================================================
    private static final Path USER_DIR =
        Paths.get(System.getProperty("user.home"), ".minecraft-i2p");

    private static final String DEFAULT_CONFIG_NAME = "minecraft-i2p.properties";
    private static final String DEFAULT_KEY_NAME = "minecraft-server.dat";
    private static final String TUNNEL_LENGTH = "1"; // hardcoded
    private static final String PLACEHOLDER_DEST = "REPLACE_WITH_YOUR_ADDRESS.b32.i2p";

    // Persistent wrapper log (support): every println/printErr line is also
    // appended to ~/.minecraft-i2p/logs/minecraft-i2p.log with a timestamp.
    // Simple rotation: past LOG_MAX_BYTES the file moves to *.old (one
    // generation kept — plenty for diagnosing the last run).
    private static final long LOG_MAX_BYTES = 1024 * 1024;
    private static PrintWriter logWriter;
    private static Path logPath;

    // Captured at class-load, BEFORE the Router is created: I2P replaces /
    // re-buffers the system streams once the router starts, and prints made
    // through System.out afterwards can end up in a buffer that is never
    // flushed to the terminal (observed in runtime tests). All wrapper output
    // goes through these original streams instead.
    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    private static void println(String s) { OUT.println(s); logLine(s); }
    private static void println() { OUT.println(); }
    private static void printErr(String s) {
        ERR.println(s);
        // Prefix only untagged lines — tagged output like [SHUTDOWN] or
        // [I2P] already identifies itself; "[ERROR] [SHUTDOWN] ..." in the
        // log would be redundant noise.
        logLine(s.startsWith("[") ? s : "[ERROR] " + s);
    }

    // ==========================================================
    // STATIC STATE (arg parsing)
    // ==========================================================
    private static String MODE = "client";
    private static String CONFIG_PATH = null;      // resolved in main
    private static String DEST_OVERRIDE = null;    // --dest=          (persists)
    private static String DEST_EPHEMERAL = null;   // --dest-override= (does not persist)

    // ==========================================================
    // INSTANCE STATE
    // ==========================================================
    private Properties config;
    private Router router;
    private TunnelControllerGroup controllerGroup;
    private TunnelController tunnelController;
    private final Object shutdownLock = new Object();
    private volatile boolean running = true;
    // Set when the router thread dies — the main loop and the warmup must
    // stop pretending everything is fine when it isn't.
    private final AtomicReference<Throwable> routerFailure = new AtomicReference<>();

    // ==========================================================
    // MAIN
    // ==========================================================
    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                MODE = arg.substring("--mode=".length()).toLowerCase();
            } else if (arg.startsWith("--config=")) {
                CONFIG_PATH = arg.substring("--config=".length());
            } else if (arg.startsWith("--dest-override=")) {
                DEST_EPHEMERAL = arg.substring("--dest-override=".length()).trim();
                validateDestination(DEST_EPHEMERAL);
            } else if (arg.startsWith("--dest=")) {
                DEST_OVERRIDE = arg.substring("--dest=".length()).trim();
                validateDestination(DEST_OVERRIDE);
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                System.exit(0);
            } else {
                printErr("[ERROR] Unrecognized option: " + arg);
                printUsage();
                System.exit(1);
            }
        }

        // Validations
        if (!MODE.equals("client") && !MODE.equals("server")) {
            printErr("Error: --mode must be 'client' or 'server'.");
            printUsage();
            System.exit(1);
        }
        if ((DEST_OVERRIDE != null || DEST_EPHEMERAL != null) && !MODE.equals("client")) {
            printErr("Error: --dest= and --dest-override= are only valid in client mode.");
            System.exit(1);
        }

        if (DEST_OVERRIDE != null && DEST_EPHEMERAL != null) {
            printErr("Error: use --dest= OR --dest-override=, not both.");
            System.exit(1);
        }

        // Create user directory if needed
        try {
            Files.createDirectories(USER_DIR);
        } catch (IOException e) {
            printErr("[ERROR] Could not create " + USER_DIR + ": " + e.getMessage());
            System.exit(1);
        }

        // Default config: inside the user directory
        if (CONFIG_PATH == null) {
            CONFIG_PATH = USER_DIR.resolve(DEFAULT_CONFIG_NAME).toString();
        }

        initLogging();

        MinecraftI2P app = new MinecraftI2P();
        try {
            app.start();
        } catch (Exception e) {
            printErr("[ERROR] " + e.getMessage());
            e.printStackTrace(ERR);
            app.shutdown();
            System.exit(1);
        }
    }

    /**
     * A .b32.i2p address is the Base32 encoding of the destination hash.
     * I2P 2.x produces the 52-char form (what Destination.toBase32() and the
     * console show); the legacy 56-char form appends "aaaa" padding. Both
     * decode to the same 32-byte hash (verified against i2p.jar Base32).
     * Catches typos early with a clear message instead of a cryptic failure
     * deep inside the tunnel code.
     */
    private static boolean isValidDestination(String dest) {
        return dest.matches("[a-z2-7]{52}(?:aaaa)?\\.b32\\.i2p");
    }

    /**
     * Command-line validation: nothing is initialized yet, so exiting
     * directly is safe and gives the clearest error.
     */
    private static void validateDestination(String dest) {
        if (!isValidDestination(dest)) {
            printErr("[ERROR] Invalid destination format: '" + dest + "'");
            printErr("        Expected: 52 base32 chars (a-z, 2-7), optionally");
            printErr("        followed by 'aaaa', then .b32.i2p");
            System.exit(1);
        }
    }

    /**
     * Config-file validation: the router and hooks may already be live by
     * the time setup runs, so throw instead of System.exit() — main's
     * catch handles cleanup consistently.
     */
    private static void validateDestinationOrThrow(String dest) throws IOException {
        if (!isValidDestination(dest)) {
            throw new IOException("Invalid destination format in config: '" + dest + "'.\n"
                + "        Expected: 52 base32 chars (a-z, 2-7), optionally\n"
                + "        followed by 'aaaa', then .b32.i2p\n"
                + "        Edit " + CONFIG_PATH + " to fix it.");
        }
    }

    private static void printUsage() {
        println("Usage: java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar [options]");
        println();
        println("Options:");
        println("  --mode=client|server       Mode (default: client)");
        println("  --dest=<b32.i2p>           Target address. CLIENT mode only.");
        println("                             Persists to config.");
        println("  --dest-override=<b32.i2p>  Target address. CLIENT mode only.");
        println("                             Does NOT persist.");
        println("  --config=<file>            Custom .properties file.");
        println("                             Relative paths resolve against");
        println("                             the current directory.");
        println("  --help, -h                 Show this help");
        println();
        println("Default files:");
        println("  Config : " + USER_DIR.resolve(DEFAULT_CONFIG_NAME));
        println("  Key    : " + USER_DIR.resolve(DEFAULT_KEY_NAME));
        println("  I2P    : " + USER_DIR.resolve("i2p_core"));
        println();
        println("Examples:");
        println("  java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest=myserver.b32.i2p");
        println("  java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --dest-override=test.b32.i2p");
        println("  java --enable-native-access=ALL-UNNAMED -jar minecraft-i2p.jar --mode=server");
        println();
        println("Note:");
        println("  --enable-native-access only matters on JDK 24+ (it silences");
        println("  a harmless jbigi warning). Omit it on JDK 17-23; on older");
        println("  JDKs it is unrecognized and the JVM will not start.");
    }

    // ==========================================================
    // MAIN FLOW
    // ==========================================================
    public void start() throws Exception {
        ensureConfigExists();
        loadConfig();
        printBanner();

        Runtime.getRuntime().addShutdownHook(
            new Thread(this::shutdown, "MinecraftI2P-Shutdown-Hook"));

        initializeRouter();
        waitForWarmup();
        setupTunnel();

        if (MODE.equals("client")) {
            printClientInstructions();
        } else {
            printServerInstructions();
        }

        while (running) {
            try {
                Thread.sleep(1000);
                // If the router thread died, don't keep waiting forever.
                Throwable failure = routerFailure.get();
                if (failure != null) {
                    printErr("[ERROR] I2P router thread failed: " + failure);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ==========================================================
    // CONFIG GENERATION / UPDATE
    // ==========================================================
    private void ensureConfigExists() throws IOException {
        Path path = Paths.get(CONFIG_PATH);

        // Case 1: file already exists
        if (Files.exists(path)) {
            if (DEST_OVERRIDE != null) {
                updateDestinationInFile(path, DEST_OVERRIDE);
            }
            return;
        }

        // Case 2: file does not exist -> generate new one
        println("[CONFIG] File not found. Generating: " + path.toAbsolutePath());

        String destination;
        if (DEST_OVERRIDE != null) {
            destination = DEST_OVERRIDE;
        } else {
            destination = PLACEHOLDER_DEST;
            println("[CONFIG] WARNING: --dest= not provided.");
            println("[CONFIG]          Edit " + CONFIG_PATH + " and set 'destination'.");
        }

        String content = buildDefaultConfig(destination);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        try (Writer out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write(content);
        }

        println("[CONFIG] File generated successfully.");
        println("[CONFIG] You can edit it to adjust the values.");
    }

    /**
     * Updates only the "destination = ..." line in the file,
     * preserving comments and other properties in their original order.
     */
    private void updateDestinationInFile(Path path, String newDest) throws IOException {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        Pattern pattern = Pattern.compile(
            "^(\\s*destination\\s*=\\s*)(.*)$",
            Pattern.MULTILINE
        );
        Matcher matcher = pattern.matcher(content);

        if (!matcher.find()) {
            println("[CONFIG] Key 'destination' not found. Appending.");
            content = content + "\n# Destination added automatically\ndestination = "
                + newDest + "\n";
        } else {
            String current = matcher.group(2).trim();
            if (current.equals(newDest)) {
                return; // nothing to do
            }
            println("[CONFIG] Updating destination:");
            println("[CONFIG]   Before: " + current);
            println("[CONFIG]   After : " + newDest);
            content = matcher.replaceFirst(
                Matcher.quoteReplacement(matcher.group(1) + newDest));
        }

        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        println("[CONFIG] File updated.");
    }

    private String buildDefaultConfig(String destination) {
        Path i2pCorePath = USER_DIR.resolve("i2p_core");
        Path keyFilePath = USER_DIR.resolve(DEFAULT_KEY_NAME);

        // Windows paths use backslashes, but this file is parsed later by
        // Properties.load(), which treats '\' as an escape character
        // ("\U" -> "U", "\n" -> newline, ...) — the raw path would come back
        // corrupted on the next run. Forward slashes work identically: every
        // consumer of these values goes through Paths.get()/java I/O, which
        // accept them on Windows too. (Properties.store() would escape them
        // for us, but we write this file by hand to keep the comments.)
        String i2pCoreStr = i2pCorePath.toString().replace('\\', '/');
        String keyFileStr = keyFilePath.toString().replace('\\', '/');

        return "# =========================================================\n"
            + "# minecraft-i2p - Unified Configuration\n"
            + "# =========================================================\n"
            + "# Automatically generated at: " + CONFIG_PATH + "\n"
            + "# Edit as needed.\n"
            + "#\n"
            + "# Keys without a prefix are SHARED between modes.\n"
            + "# 'destination' is used only in CLIENT mode.\n"
            + "# 'keyFile' is used only in SERVER mode.\n"
            + "# =========================================================\n"
            + "\n"
            + "# ---------- SHARED ----------\n"
            + "host = 127.0.0.1\n"
            + "port = 25565\n"
            + "\n"
            + "# I2P router state directory (can be deleted,\n"
            + "# but this forces a full reseed on next run).\n"
            + "i2pConfigDir = " + i2pCoreStr + "\n"
            + "\n"
            + "# I2P network warmup\n"
            + "warmupTimeoutSeconds = 300\n"
            + "warmupCheckIntervalSeconds = 10\n"
            + "\n"
            + "# ---------- TUNNEL ----------\n"
            + "# Friendly tunnel name (shown in the I2P console)\n"
            + "tunnelName = Minecraft\n"
            + "\n"
            + "# Number of parallel tunnels (higher throughput, more CPU/bandwidth)\n"
            + "# NOTE: length is always 1 (hardcoded). Not configurable.\n"
            + "inboundQuantity = 2\n"
            + "outboundQuantity = 2\n"
            + "\n"
            + "# ---------- CLIENT ----------\n"
            + "# .b32.i2p address of the destination server (CLIENT mode)\n"
            + "destination = " + destination + "\n"
            + "\n"
            + "# Reuse tunnel for multiple simultaneous connections\n"
            + "sharedClient = true\n"
            + "\n"
            + "# ---------- SERVER ----------\n"
            + "# Private key file (SERVER mode)\n"
            + "# WARNING: back it up! This file defines your .b32.i2p address.\n"
            + "keyFile = " + keyFileStr + "\n"
            + "\n"
            + "# Friendly key name (shown in I2P as the destination name)\n"
            + "keyName = minecraft-server\n";
    }

    // ==========================================================
    // CONFIG LOADING
    // ==========================================================
    private void loadConfig() throws IOException {
        Path path = Paths.get(CONFIG_PATH);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }
        config = new Properties();
        // Reader/Writer API: UTF-8 correct (the InputStream/OutputStream API
        // is ISO-8859-1 per the Properties spec and would mangle non-ASCII paths).
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config.load(in);
        }
    }

    private String required(String key) throws IOException {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Required property missing: " + key);
        }
        return value.trim();
    }

    private String optional(String key, String defaultValue) {
        String value = config.getProperty(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private int optionalInt(String key, int defaultValue) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            printErr("[CONFIG] Invalid value for " + key + ": '" + value + "'");
            return defaultValue;
        }
    }

    private boolean optionalBool(String key, boolean defaultValue) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        value = value.trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        printErr("[CONFIG] Invalid boolean for " + key + ": '" + value
            + "'. Using default: " + defaultValue);
        return defaultValue;
    }

    // ==========================================================
    // WRAPPER LOG
    // ==========================================================
    private static void initLogging() {
        try {
            Path dir = USER_DIR.resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("minecraft-i2p.log");
            if (Files.exists(file) && Files.size(file) > LOG_MAX_BYTES) {
                Files.move(file, dir.resolve("minecraft-i2p.log.old"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            logPath = file;
            logWriter = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                true); // autoFlush — crash-resilient
        } catch (IOException e) {
            // Logging is best-effort: never stop the app because of it.
            logWriter = null;
            logPath = null;
        }
    }

    private static void logLine(String s) {
        PrintWriter w = logWriter;
        if (w == null) return;
        // Timestamp every line so entries stay in order even when the
        // console text is indistinguishable (repeated waits, blank-ish lines).
        w.println(new java.util.Date() + " " + s);
    }

    private static void closeLogging() {
        PrintWriter w = logWriter;
        if (w != null) {
            logWriter = null;
            w.close();
        }
    }

    // ==========================================================
    // BANNER
    // ==========================================================
    private void printBanner() {
        println("========================================");
        println("  minecraft-i2p");
        println("  Mode   : " + MODE.toUpperCase());
        println("  Config : " + CONFIG_PATH);
        println("  Data   : " + USER_DIR);
        if (logPath != null) println("  Log    : " + logPath);
        println("  Length : " + TUNNEL_LENGTH + " (fixed)");
        println("========================================");
    }

    // ==========================================================
    // I2P ROUTER
    // ==========================================================
    private void initializeRouter() throws Exception {
        String configDir = optional("i2pConfigDir",
            USER_DIR.resolve("i2p_core").toString());

        println("[I2P] Config directory: " + configDir);
        Path dirPath = Paths.get(configDir);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        System.setProperty("i2p.dir.config", dirPath.toAbsolutePath().toString());
        System.setProperty("i2p.dir.base", dirPath.toAbsolutePath().toString());
        System.setProperty("java.net.preferIPv4Stack", "true");

        // The web console cannot be enabled here on purpose: this fat JAR
        // bundles no Jetty/console webapps, so consolehtml would never bind.
        // The server's .b32.i2p address is printed directly by the wrapper
        // (see printServerInstructions) instead.
        println("[I2P] Starting embedded router (headless)...");
        try {
            router = new Router();
        } catch (Exception e) {
            throw new IOException("Failed to create I2P router: " + e.getMessage(), e);
        }

        // CRITICAL for embedded use: prevent the router from killing the JVM
        // on its own. We want our shutdown hook to control the lifecycle.
        try {
            router.setKillVMOnEnd(false);
        } catch (Throwable t) {
            println("[I2P] Note: setKillVMOnEnd unavailable: " + t.getMessage());
        }

        // Router.runRouter() blocks until the router shuts down, so it MUST
        // run in its own thread — otherwise setupTunnel() is never reached.
        //
        // Throwable (not Exception) on purpose: this thread is the last
        // witness to a dying router. We record the failure so the main loop
        // and warmup stop pretending everything is fine, then print it while
        // stderr still works — swallowing an Error here is better than an
        // unreported thread death, since nothing else in the JVM notices.
        Thread routerThread = new Thread(() -> {
            try {
                router.runRouter();
            } catch (Throwable t) {
                routerFailure.set(t);
                printErr("[I2P] Router terminated: " + t);
                t.printStackTrace(ERR);
            }
        }, "I2P-Router");
        routerThread.setDaemon(true);
        routerThread.start();

        println("[I2P] Router thread started.");
    }

    // ==========================================================
    // TUNNEL
    // ==========================================================
    private void setupTunnel() throws IOException {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);
        String tunnelName = optional("tunnelName", "Minecraft");
        int inboundQuantity = optionalInt("inboundQuantity", 2);
        int outboundQuantity = optionalInt("outboundQuantity", 2);

        if (inboundQuantity < 1) inboundQuantity = 1;
        if (outboundQuantity < 1) outboundQuantity = 1;

        Properties opts = new Properties();

        // Friendly tunnel name (top-level key, read by TunnelController.PROP_NAME)
        opts.setProperty("name", tunnelName);

        // Tunnel-pool options DO use the "option." prefix — this is the one
        // place it belongs (advanced options forwarded to the tunnel pool,
        // same format the I2P web console writes to i2ptunnel.config).
        opts.setProperty("option.inbound.length", TUNNEL_LENGTH);
        opts.setProperty("option.outbound.length", TUNNEL_LENGTH);
        opts.setProperty("option.inbound.quantity", String.valueOf(inboundQuantity));
        opts.setProperty("option.outbound.quantity", String.valueOf(outboundQuantity));
        opts.setProperty("option.inbound.backupQuantity", "0");
        opts.setProperty("option.outbound.backupQuantity", "0");

        if (MODE.equals("client")) {
            setupClientTunnel(opts, host, port);
        } else {
            setupServerTunnel(opts, host, port);
        }
    }

    private void setupClientTunnel(Properties opts, String host, int port) throws IOException {
        String destination;
        if (DEST_EPHEMERAL != null) {
            destination = DEST_EPHEMERAL;
            println("[I2P] Using ephemeral destination (not persisted): " + destination);
        } else {
            destination = required("destination");
            if (destination.equals(PLACEHOLDER_DEST)) {
                throw new IOException("No server address configured yet.\n"
                    + "Edit " + CONFIG_PATH + " and set 'destination = <server>.b32.i2p',\n"
                    + "or run with: --dest=<server>.b32.i2p");
            }
            validateDestinationOrThrow(destination);
        }

        boolean sharedClient = optionalBool("sharedClient", true);

        println("[I2P] Creating CLIENT tunnel...");
        println("[I2P]   Listen  : " + host + ":" + port);
        println("[I2P]   Target  : " + destination);
        println("[I2P]   Length  : " + TUNNEL_LENGTH + " (fixed)");
        println("[I2P]   Quantity: " + inboundQuantityStr(opts));

        // Client-type tunnels listen locally and forward to a remote dest.
        // These are TOP-LEVEL TunnelController keys (its PROP_* constants are
        // public — verified against i2ptunnel.jar 2.13.0): the "option."
        // prefix does NOT apply here (TunnelController would report
        // "Cannot start the tunnel - no type specified").
        // Note the remote destination key is PROP_DEST = "targetDestination",
        // NOT "destination". The local bind interface is PROP_INTFC;
        // I2PTunnel otherwise defaults to 127.0.0.1.
        opts.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_STD_CLIENT);
        opts.setProperty(TunnelController.PROP_INTFC, host);
        opts.setProperty(TunnelController.PROP_LISTEN_PORT, String.valueOf(port));
        opts.setProperty(TunnelController.PROP_DEST, destination);
        opts.setProperty(TunnelController.PROP_SHARED, String.valueOf(sharedClient));

        startController(opts);
        println("[I2P] Client tunnel registered — building in the background...");
    }

    private void setupServerTunnel(Properties opts, String host, int port) throws IOException {
        String keyFile = required("keyFile");
        String keyName = optional("keyName", "minecraft-server");

        println("[I2P] Creating SERVER tunnel...");
        println("[I2P]   Key     : " + keyFile);
        println("[I2P]   Forward : " + host + ":" + port);
        println("[I2P]   Length  : " + TUNNEL_LENGTH + " (fixed)");
        println("[I2P]   Quantity: " + inboundQuantityStr(opts));

        Path keyPath = Paths.get(keyFile);
        if (!Files.exists(keyPath)) {
            println("[I2P] New key. Will be created at: " + keyPath.toAbsolutePath());
            Path parent = keyPath.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    // Raw exceptions from here surface as just the path
                    // ("[ERROR] /nonexistent-dir") — say what failed and where
                    // to fix it.
                    throw new IOException("Cannot create the directory for 'keyFile': "
                        + parent + " (" + e + ")\n"
                        + "        Fix the keyFile entry in " + CONFIG_PATH, e);
                }
            }
        } else {
            println("[I2P] Key found. Address preserved.");
        }

        // Server-type tunnels accept I2P connections and forward them to the
        // local Minecraft server. Same rule as the client: top-level keys,
        // no "option." prefix.
        opts.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_STD_SERVER);
        opts.setProperty(TunnelController.PROP_TARGET_HOST, host);
        opts.setProperty(TunnelController.PROP_TARGET_PORT, String.valueOf(port));
        opts.setProperty("privKeyFile", keyFile);
        opts.setProperty("keyName", keyName);

        startController(opts);
        println("[I2P] Server tunnel registered — building in the background...");
    }

    private String inboundQuantityStr(Properties opts) {
        return opts.getProperty("option.inbound.quantity")
            + "/" + opts.getProperty("option.outbound.quantity");
    }

    private void startController(Properties opts) throws IOException {
        // Wait for the I2P context to be fully populated.
        // The ClientAppManager is required by the TunnelControllerGroup
        // constructor and only exists once the router thread has started it.
        I2PAppContext ctx = null;
        ClientAppManager mgr = null;

        int waitedSec = 0;
        while (waitedSec < 30) {
            if (routerFailure.get() != null) {
                throw new IOException("I2P router thread failed before tunnel "
                    + "setup could begin: " + routerFailure.get());
            }
            ctx = I2PAppContext.getGlobalContext();
            mgr = ctx.clientAppManager();
            if (mgr != null) break;
            println("[I2P] Waiting for I2P app manager... (" + waitedSec + "s)");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the I2P app manager", e);
            }
            waitedSec++;
        }

        if (mgr == null) {
            throw new IOException("I2P ClientAppManager still null after " + waitedSec
                + "s. The router may have failed to start (check messages above).");
        }

        try {
            controllerGroup = new TunnelControllerGroup(ctx, mgr, new String[0]);
            tunnelController = new TunnelController(opts, "");
            controllerGroup.addController(tunnelController);
            // Background, NOT plain startTunnel(): startTunnel() blocks until
            // the tunnel is actually built — which on a first run (router
            // still reseeding, no peers) can take many minutes and would
            // freeze the wrapper before any instructions are printed.
            // The tunnel builds itself as soon as the network is ready.
            tunnelController.startTunnelBackground();
        } catch (Exception e) {
            throw new IOException("Failed to start I2P tunnel: " + e, e);
        }

        startTunnelMonitor();
    }

    /**
     * startTunnelBackground() returns before the tunnel exists, so the
     * wrapper would otherwise claim "active" and stay silent while the real
     * build succeeds or fails asynchronously. This watcher reports the
     * outcome: ACTIVE when the tunnel comes up, and a warning if it never
     * does (unreachable destination, router still reseeding).
     */
    private void startTunnelMonitor() {
        Thread monitor = new Thread(() -> {
            final int maxWaitSec = 600;   // first run (reseed) can be slow
            final int pollMs = 2000;
            // Floor at 1s: a sub-second pollMs would make pollMs/1000 == 0
            // and the loop counter would never advance (infinite loop).
            final int pollSec = Math.max(1, pollMs / 1000);
            boolean sixtySecsNoted = false;
            for (int waited = 0; waited < maxWaitSec && running; waited += pollSec) {
                if (routerFailure.get() != null) {
                    printErr("[ERROR] Router failed — the tunnel will not come up.");
                    return;
                }
                boolean starting = tunnelController.getIsStarting();
                boolean isRunning = tunnelController.getIsRunning();
                if (isRunning) {
                    println("[I2P] Tunnel is now ACTIVE — connections can flow.");
                    return;
                }
                // Neither starting nor running (after a grace period for the
                // background thread to flip the state) means it gave up:
                // STOPPED/DESTROYED — a real failure, not just slow.
                if (!starting && waited >= 10) {
                    printErr("[ERROR] Tunnel build failed (state: stopped). Common causes:");
                    printErr("        - the target address is offline or mistyped");
                    printErr("        - the router has not finished joining I2P yet");
                    printErr("        Check the log: " + logPath);
                    return;
                }
                // Flag instead of waited == 60: exact equality would break if
                // pollMs ever stopped dividing evenly into 60.
                if (!sixtySecsNoted && waited >= 60) {
                    println("[I2P] Tunnel still building — first runs take a few");
                    println("      minutes while the router joins the network.");
                    sixtySecsNoted = true;
                }
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (running) {
                println("[I2P] NOTE: tunnel is still not active after "
                    + (maxWaitSec / 60) + " minutes. Minecraft connections");
                println("      will fail until it is. It keeps retrying in the");
                println("      background — watch for the ACTIVE message.");
            }
        }, "Tunnel-Monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    // ==========================================================
    // WARMUP
    // ==========================================================
    private void waitForWarmup() throws IOException {
        int timeoutSec = optionalInt("warmupTimeoutSeconds", 300);
        int intervalSec = optionalInt("warmupCheckIntervalSeconds", 10);

        println("[WARMUP] Waiting for I2P router to become alive...");
        println("[WARMUP] Max timeout: " + timeoutSec + "s");
        println("[WARMUP] First run can take several minutes (reseed).");

        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        long intervalMs = intervalSec * 1000L;
        int attempt = 0;

        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++;
            Throwable failure = routerFailure.get();
            if (failure != null) {
                throw new IOException("I2P router thread failed during warmup: " + failure);
            }
            boolean alive = isRouterAlive();
            OUT.printf("[WARMUP] #%d - Router alive: %s%n", attempt, alive);

            if (alive) {
                println("[WARMUP] Router is alive.");
                return;
            }

            try { Thread.sleep(intervalMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during warmup", e);
            }
        }

        println("[WARMUP] Timeout waiting for router. Continuing — the tunnel");
        println("         will build as the network comes up.");
    }

    private boolean isRouterAlive() {
        try {
            return router != null && router.isAlive();
        } catch (Exception e) {
            printErr("[WARMUP] Error checking router state: " + e);
            return false;
        }
    }

    // ==========================================================
    // INSTRUCTIONS
    // ==========================================================
    private void printClientInstructions() {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);
        println();
        println("========================================");
        println("  READY TO PLAY");
        println("========================================");
        println("  1. Open Minecraft Java Edition");
        println("  2. Add a server at:");
        println("       " + host + ":" + port);
        println("  3. Connect — but WAIT for the line");
        println("       '[I2P] Tunnel is now ACTIVE' below");
        println("       first (first runs take a few minutes).");
        println();
        println("  Keep this window open while playing.");
        println("  Press Ctrl+C to stop.");
        println("========================================");
    }

    private void printServerInstructions() {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);

        println();
        println("========================================");
        println("  SERVER READY");
        println("========================================");
        println("  1. Start your Minecraft server manually");
        println("     (e.g. java -Xmx2G -jar server.jar nogui)");
        println("  2. It must listen on " + host + ":" + port);
        println("  3. Set online-mode=false in server.properties");
        println();
        println("  --- Your server's .b32.i2p address ---");
        String b32 = waitForServerB32Address(30);
        if (b32 != null) {
            println("  " + b32);
            println();
            println("  Players connect using:");
            println("      java -jar minecraft-i2p.jar --dest=" + b32);
        } else {
            println("  (no address yet — the key file was not generated within");
            println("   30s. Run the program again in a few minutes.)");
        }
        println();
        println("  NOTE: the first tunnel build can take several");
        println("  minutes while the router joins the network.");
        println("  Press Ctrl+C to stop.");
        println("========================================");
    }

    /**
     * On a FIRST server run the key file is created by the tunnel's
     * background startup (startTunnelBackground returns immediately), so it
     * may not exist yet when instructions are printed. Poll briefly instead
     * of showing "not available" for something that appears seconds later.
     */
    private String waitForServerB32Address(int maxWaitSec) {
        // Only announce the wait when there actually is one — on every run
        // after the first the key already exists and this returns instantly.
        String b32 = getServerB32Address();
        if (b32 != null) return b32;
        // Box-friendly wording: this only ever prints inside the indented
        // SERVER READY block, so match its style instead of the [I2P] log
        // prefix used elsewhere.
        println("  (generating the key, please wait...)");
        for (int waited = 0; waited < maxWaitSec; waited++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            b32 = getServerB32Address();
            if (b32 != null) return b32;
        }
        return getServerB32Address();
    }

    /**
     * The .b32.i2p address is fully determined by the server private key
     * file, so we derive it directly from the key instead of waiting for the
     * tunnel's I2P session (which only exists once the network is reachable).
     * Destination.create(InputStream) is the same factory I2P uses to read
     * key files; toBase32() is what the console displays (via
     * TunnelController.getMyDestHashBase32()).
     * Returns null if the key does not exist (yet) — safe to poll.
     */
    private String getServerB32Address() {
        String keyFile = config != null ? config.getProperty("keyFile") : null;
        if (keyFile == null || keyFile.isBlank()) return null;
        Path path = Paths.get(keyFile.trim());
        if (!Files.exists(path)) return null;
        try (InputStream in = Files.newInputStream(path)) {
            net.i2p.data.Destination dst = net.i2p.data.Destination.create(in);
            String b32 = dst.toBase32();
            if (b32 == null || b32.isEmpty()) return null;
            return b32.endsWith(".b32.i2p") ? b32 : b32 + ".b32.i2p";
        } catch (Exception e) {
            // Key may be half-written while the background tunnel creates it.
            return null;
        }
    }

    // ==========================================================
    // SHUTDOWN
    // ==========================================================
    public void shutdown() {
        synchronized (shutdownLock) {
            if (!running) return;
            running = false;
            println("\n[SHUTDOWN] Stopping I2P services...");

            if (tunnelController != null) {
                // stopTunnel() can block while a background build is in
                // flight — run it with a deadline. The router shutdown
                // tears tunnels down anyway, so we never wait long.
                Thread stopper = new Thread(tunnelController::stopTunnel, "Tunnel-Stopper");
                stopper.setDaemon(true);
                stopper.start();
                try {
                    stopper.join(5000);
                    if (stopper.isAlive()) println("[SHUTDOWN] Tunnel stop timed out; router shutdown will finish it.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (router != null) {
                println("[SHUTDOWN] Stopping router (graceful)...");
                try {
                    router.shutdownGracefully();
                } catch (Exception e) {
                    printErr("[SHUTDOWN] Error stopping router: " + e.getMessage());
                }
                router = null;
                println("[SHUTDOWN] Router stopped.");
            }

            println("[SHUTDOWN] Done.");
            closeLogging();
        }
    }
}