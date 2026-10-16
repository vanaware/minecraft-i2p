import net.i2p.i2ptunnel.TunnelController;
import net.i2p.i2ptunnel.TunnelControllerGroup;
import net.i2p.router.Router;
import net.i2p.util.Log;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * minecraft-i2p - Universal I2P wrapper for Minecraft
 *
 * Usage:
 *   java -jar minecraft-i2p.jar                                 -> CLIENT (default)
 *   java -jar minecraft-i2p.jar --dest=X.b32.i2p                -> CLIENT + save destination
 *   java -jar minecraft-i2p.jar --dest-override=X.b32.i2p       -> CLIENT + temporary destination
 *   java -jar minecraft-i2p.jar --mode=server                   -> SERVER
 *   java -jar minecraft-i2p.jar --config=custom.properties      -> custom config
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

    // ==========================================================
    // MAIN
    // ==========================================================
    public static void main(String[] args) {
        // Parse arguments (before resolving paths)
        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                MODE = arg.substring("--mode=".length()).toLowerCase();
            } else if (arg.startsWith("--config=")) {
                CONFIG_PATH = arg.substring("--config=".length());
            } else if (arg.startsWith("--dest-override=")) {
                DEST_EPHEMERAL = arg.substring("--dest-override=".length()).trim();
            } else if (arg.startsWith("--dest=")) {
                DEST_OVERRIDE = arg.substring("--dest=".length()).trim();
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                System.exit(0);
            }
        }

        // Validations
        if (!MODE.equals("client") && !MODE.equals("server")) {
            System.err.println("Error: --mode must be 'client' or 'server'.");
            printUsage();
            System.exit(1);
        }

        if ((DEST_OVERRIDE != null || DEST_EPHEMERAL != null) && !MODE.equals("client")) {
            System.err.println("Error: --dest= and --dest-override= are only valid in client mode.");
            System.exit(1);
        }

        if (DEST_OVERRIDE != null && DEST_EPHEMERAL != null) {
            System.err.println("Error: use --dest= OR --dest-override=, not both.");
            System.exit(1);
        }

        // Create user directory if needed
        try {
            Files.createDirectories(USER_DIR);
        } catch (IOException e) {
            System.err.println("[ERROR] Could not create " + USER_DIR + ": "
                + e.getMessage());
            System.exit(1);
        }

        // Default config: inside the user directory
        if (CONFIG_PATH == null) {
            CONFIG_PATH = USER_DIR.resolve(DEFAULT_CONFIG_NAME).toString();
        }

        MinecraftI2P app = new MinecraftI2P();
        try {
            app.start();
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            app.shutdown();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar minecraft-i2p.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --mode=client|server       Mode (default: client)");
        System.out.println("  --dest=<b32.i2p>           Client destination. Persists to config.");
        System.out.println("  --dest-override=<b32.i2p>  Client destination. Does NOT persist.");
        System.out.println("  --config=<file>            Custom .properties file");
        System.out.println("  --help, -h                 Show this help");
        System.out.println();
        System.out.println("Default files:");
        System.out.println("  Config : " + USER_DIR.resolve(DEFAULT_CONFIG_NAME));
        System.out.println("  Key    : " + USER_DIR.resolve(DEFAULT_KEY_NAME));
        System.out.println("  I2P    : " + USER_DIR.resolve("i2p_core"));
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar minecraft-i2p.jar --dest=myserver.b32.i2p");
        System.out.println("  java -jar minecraft-i2p.jar --dest-override=test.b32.i2p");
        System.out.println("  java -jar minecraft-i2p.jar --mode=server");
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
        setupTunnel();
        waitForWarmup();

        if (MODE.equals("client")) {
            printClientInstructions();
        } else {
            printServerInstructions();
        }

        while (running) {
            try {
                Thread.sleep(1000);
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
        System.out.println("[CONFIG] File not found. Generating: "
            + path.toAbsolutePath());

        String destination;
        if (DEST_OVERRIDE != null) {
            destination = DEST_OVERRIDE;
        } else {
            destination = PLACEHOLDER_DEST;
            System.out.println("[CONFIG] WARNING: --dest= not provided.");
            System.out.println("[CONFIG]          Edit " + CONFIG_PATH
                + " and set 'destination'.");
        }

        String content = buildDefaultConfig(destination);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("[CONFIG] File generated successfully.");
        System.out.println("[CONFIG] You can edit it to adjust the values.");
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
            System.out.println("[CONFIG] Key 'destination' not found. Appending.");
            content = content + "\n# Destination added automatically\ndestination = "
                + newDest + "\n";
        } else {
            String current = matcher.group(2).trim();
            if (current.equals(newDest)) {
                return; // nothing to do
            }
            System.out.println("[CONFIG] Updating destination:");
            System.out.println("[CONFIG]   Before: " + current);
            System.out.println("[CONFIG]   After : " + newDest);
            content = matcher.replaceFirst(
                Matcher.quoteReplacement(matcher.group(1) + newDest));
        }

        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        System.out.println("[CONFIG] File updated.");
    }

    private String buildDefaultConfig(String destination) {
        Path i2pCorePath = USER_DIR.resolve("i2p_core");
        Path keyFilePath = USER_DIR.resolve(DEFAULT_KEY_NAME);

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
            + "i2pConfigDir = " + i2pCorePath + "\n"
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
            + "keyFile = " + keyFilePath + "\n"
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
            throw new IOException("Configuration file not found: "
                + path.toAbsolutePath());
        }
        config = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
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
            System.err.println("[CONFIG] Invalid value for " + key
                + ": '" + value + "'. Using default: " + defaultValue);
            return defaultValue;
        }
    }

    private boolean optionalBool(String key, boolean defaultValue) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Boolean.parseBoolean(value.trim());
    }

    // ==========================================================
    // BANNER
    // ==========================================================
    private void printBanner() {
        System.out.println("========================================");
        System.out.println("  minecraft-i2p");
        System.out.println("  Mode   : " + MODE.toUpperCase());
        System.out.println("  Config : " + CONFIG_PATH);
        System.out.println("  Data   : " + USER_DIR);
        System.out.println("  Length : " + TUNNEL_LENGTH + " (fixed)");
        System.out.println("========================================");
    }

    // ==========================================================
    // I2P ROUTER
    // ==========================================================
    private void initializeRouter() throws Exception {
        String configDir = optional("i2pConfigDir",
            USER_DIR.resolve("i2p_core").toString());

        System.out.println("[I2P] Config directory: " + configDir);
        Path dirPath = Paths.get(configDir);
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        System.setProperty("i2p.dir.config", dirPath.toAbsolutePath().toString());
        System.setProperty("i2p.dir.base", dirPath.toAbsolutePath().toString());
        System.setProperty("java.net.preferIPv4Stack", "true");

        // Enable the web console ONLY in server mode — that's where the
        // user needs to find the .b32.i2p address of the tunnel.
        // In client mode, the wrapper already prints everything the user
        // needs in the terminal, so the console would just be noise.
        if (MODE.equals("server")) {
            System.setProperty("i2p.router.console", "true");
        } else {
            System.setProperty("i2p.router.console", "false");
        }

        System.out.println("[I2P] Starting embedded router (headless)...");
        try {
            router = new Router();
        } catch (Exception e) {
            throw new Exception("Failed to create I2P router: " + e.getMessage(), e);
        }

        // Router.runRouter() blocks until the router shuts down, so it MUST
        // run in its own thread — otherwise setupTunnel() is never reached.
        Thread routerThread = new Thread(() -> {
            try {
                router.runRouter();
            } catch (Exception e) {
                System.err.println("[I2P] Router terminated: " + e.getMessage());
                e.printStackTrace();
            }
        }, "I2P-Router");
        routerThread.start();

        System.out.println("[I2P] Router thread started.");
        if (MODE.equals("server")) {
            System.out.println("[I2P] Web console: http://127.0.0.1:7657");
        }
    }

    // ==========================================================
    // TUNNEL
    // ==========================================================
    private void setupTunnel() throws Exception {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);
        String tunnelName = optional("tunnelName", "Minecraft");
        int inboundQuantity = optionalInt("inboundQuantity", 2);
        int outboundQuantity = optionalInt("outboundQuantity", 2);

        if (inboundQuantity < 1) inboundQuantity = 1;
        if (outboundQuantity < 1) outboundQuantity = 1;

        Properties opts = new Properties();
        opts.setProperty("name", tunnelName);
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

    private void setupClientTunnel(Properties opts, String host, int port) throws Exception {
        String destination;
        if (DEST_EPHEMERAL != null) {
            destination = DEST_EPHEMERAL;
            System.out.println("[I2P] Using ephemeral destination (not persisted): " + destination);
        } else {
            destination = required("destination");
        }

        boolean sharedClient = optionalBool("sharedClient", true);

        System.out.println("[I2P] Creating CLIENT tunnel...");
        System.out.println("[I2P]   Listen  : " + host + ":" + port);
        System.out.println("[I2P]   Target  : " + destination);
        System.out.println("[I2P]   Length  : " + TUNNEL_LENGTH + " (fixed)");
        System.out.println("[I2P]   Quantity: " + inboundQuantityStr(opts));

        // Client-type tunnels listen locally, so the keys are listenHost/listenPort
        // (targetHost/targetPort are server-type keys) and the remote endpoint
        // is 'targetDestination' (TunnelController.PROP_DEST).
        opts.setProperty("type", "client");
        opts.setProperty("listenHost", host);
        opts.setProperty("listenPort", String.valueOf(port));
        opts.setProperty("targetDestination", destination);
        opts.setProperty("sharedClient", String.valueOf(sharedClient));

        startController(opts);
        System.out.println("[I2P] Client tunnel active.");
    }

    private void setupServerTunnel(Properties opts, String host, int port) throws Exception {
        String keyFile = required("keyFile");
        String keyName = optional("keyName", "minecraft-server");

        System.out.println("[I2P] Creating SERVER tunnel...");
        System.out.println("[I2P]   Key     : " + keyFile);
        System.out.println("[I2P]   Forward : " + host + ":" + port);
        System.out.println("[I2P]   Length  : " + TUNNEL_LENGTH + " (fixed)");
        System.out.println("[I2P]   Quantity: " + inboundQuantityStr(opts));

        Path keyPath = Paths.get(keyFile);
        if (!Files.exists(keyPath)) {
            System.out.println("[I2P] New key. Will be created at: " + keyPath.toAbsolutePath());
            Path parent = keyPath.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } else {
            System.out.println("[I2P] Key found. Address preserved.");
        }

        opts.setProperty("type", "server");
        opts.setProperty("targetHost", host);
        opts.setProperty("targetPort", String.valueOf(port));
        opts.setProperty("privKeyFile", keyFile);
        opts.setProperty("keyName", keyName);

        startController(opts);
        System.out.println("[I2P] Server tunnel active.");
    }

    private String inboundQuantityStr(Properties opts) {
        return opts.getProperty("option.inbound.quantity")
            + "/" + opts.getProperty("option.outbound.quantity");
    }

    private void startController(Properties opts) throws Exception {
        // Get the global I2P context. After the router has started,
        // this context is fully initialized and bound to the running router.
        net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();

        // The ClientAppManager is required by the TunnelControllerGroup constructor.
        // In the global I2PAppContext it is non-null.
        net.i2p.app.ClientAppManager mgr = ctx.clientAppManager();
        if (mgr == null) {
            throw new Exception("I2P ClientAppManager is null. Is the router running?");
        }

        // Create the group with the required constructor arguments.
        // The String[] args can be empty — we're not loading tunnels from a config file.
        controllerGroup = new TunnelControllerGroup(ctx, mgr, new String[0]);

        // Create the individual tunnel controller with our Properties.
        tunnelController = new TunnelController(opts, "");

        // Register the controller with the group.
        controllerGroup.addController(tunnelController);

        // Start the tunnel.
        tunnelController.startTunnel();
    }

    // ==========================================================
    // WARMUP
    // ==========================================================
    private void waitForWarmup() {
        int timeoutSec = optionalInt("warmupTimeoutSeconds", 300);
        int intervalSec = optionalInt("warmupCheckIntervalSeconds", 10);

        System.out.println("[WARMUP] Waiting for I2P network integration...");
        System.out.println("[WARMUP] Max timeout: " + timeoutSec + "s");

        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        long intervalMs = intervalSec * 1000L;
        int attempt = 0;

        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++;
            boolean alive = isRouterAlive();
            System.out.printf("[WARMUP] #%d - Router: %s%n",
                attempt, alive ? "ALIVE" : "STARTING");

            if (alive) {
                try { Thread.sleep(5000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println("[WARMUP] Network ready.");
                return;
            }

            try { Thread.sleep(intervalMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.err.println("[WARMUP] Timeout. Proceeding anyway.");
    }

    private boolean isRouterAlive() {
        try {
            return router != null && router.isAlive();
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================================
    // INSTRUCTIONS
    // ==========================================================
    private void printClientInstructions() {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);
        System.out.println();
        System.out.println("========================================");
        System.out.println("  READY TO PLAY");
        System.out.println("========================================");
        System.out.println("  1. Open Minecraft Java Edition");
        System.out.println("  2. Add a server at:");
        System.out.println("       " + host + ":" + port);
        System.out.println("  3. Connect.");
        System.out.println();
        System.out.println("  Keep this window open while playing.");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println("========================================");
    }

    private void printServerInstructions() {
        String host = optional("host", "127.0.0.1");
        int port = optionalInt("port", 25565);
        String tunnelName = optional("tunnelName", "Minecraft");

        System.out.println();
        System.out.println("========================================");
        System.out.println("  SERVER READY");
        System.out.println("========================================");
        System.out.println("  1. Start your Minecraft server manually");
        System.out.println("     (e.g. java -Xmx2G -jar server.jar nogui)");
        System.out.println("  2. It must listen on " + host + ":" + port);
        System.out.println("  3. Set online-mode=false in server.properties");
        System.out.println();
        System.out.println("  --- How to find your .b32.i2p address ---");
        System.out.println("  Open this URL in your browser:");
        System.out.println("      http://127.0.0.1:7657/i2ptunnelmgr");
        System.out.println();
        System.out.println("  Look for the tunnel named: \"" + tunnelName + "\"");
        System.out.println("  Copy the .b32.i2p address shown next to it.");
        System.out.println("  Share it with your players.");
        System.out.println();
        System.out.println("  Players connect using:");
        System.out.println("      java -jar minecraft-i2p.jar --dest=<that-address>.b32.i2p");
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println("========================================");
    }

    // ==========================================================
    // SHUTDOWN
    // ==========================================================
    public void shutdown() {
        synchronized (shutdownLock) {
            if (!running) return;
            running = false;
            System.out.println("\n[SHUTDOWN] Stopping I2P services...");

            if (tunnelController != null) {
                try {
                    tunnelController.stopTunnel();
                } catch (Exception e) {
                    System.err.println("[SHUTDOWN] Error stopping tunnel: " + e.getMessage());
                }
            }

            if (router != null) {
                System.out.println("[SHUTDOWN] Stopping router (graceful)...");
                try {
                    router.shutdownGracefully();
                } catch (Exception e) {
                    System.err.println("[SHUTDOWN] Error stopping router: " + e.getMessage());
                }
                router = null;
                System.out.println("[SHUTDOWN] Router stopped.");
            }

            System.out.println("[SHUTDOWN] Done.");
        }
    }
}