package adris.altoclef.testharness;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.mixins.CreateWorldScreenAccessor;
import adris.altoclef.tasks.speedrun.BeatMinecraft2Task;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Consumer;

public class GamerTestHarness {

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AltoClef mod;
    private final Config config;
    private final BufferedWriter writer;
    private final Path logPath;
    private final long startedAtMs = System.currentTimeMillis();
    private final Consumer<Debug.LogEntry> logListener = this::onDebugLog;

    private boolean worldCreateRequested;
    private boolean worldCreateStarted;
    private boolean accessibilityOnboardingDismissed;
    private int worldCreateScreenTicks;
    private boolean commandStarted;
    private boolean finished;
    private int ticks;
    private long lastProgressAtMs;
    private String lastProgressKey = "";
    private String lastWarning = "";
    private int repeatedWarningCount;

    private GamerTestHarness(AltoClef mod, Config config, Path logPath, BufferedWriter writer) {
        this.mod = mod;
        this.config = config;
        this.logPath = logPath;
        this.writer = writer;
        this.lastProgressAtMs = startedAtMs;

        Debug.addLogListener(logListener);
        EventBus.subscribe(TaskFinishedEvent.class, this::onTaskFinished);

        writeEvent("harness_start",
                field("command", config.command),
                field("createWorld", config.createWorld),
                field("worldName", config.worldName),
                field("stallSeconds", config.stallSeconds),
                field("maxRunSeconds", config.maxRunSeconds),
                field("logPath", logPath.toString()));
        Debug.logInternal("[GamerHarness] enabled, writing " + logPath);
    }

    public static GamerTestHarness createIfEnabled(AltoClef mod) {
        Config config = Config.load();
        if (!config.enabled) {
            return null;
        }

        try {
            Path directory = MinecraftClient.getInstance().runDirectory.toPath().resolve("altoclef-harness");
            Files.createDirectories(directory);
            String filename = "gamer-" + FILE_TIME_FORMAT.format(Instant.now()) + ".jsonl";
            Path logPath = directory.resolve(filename);
            BufferedWriter writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8);
            return new GamerTestHarness(mod, config, logPath, writer);
        } catch (IOException e) {
            Debug.logError("[GamerHarness] Failed to initialize: " + e);
            return null;
        }
    }

    public void tick() {
        if (finished) {
            return;
        }

        ticks++;
        MinecraftClient client = MinecraftClient.getInstance();

        if (config.createWorld && !AltoClef.inGame()) {
            tickWorldCreation(client);
        }

        if (!commandStarted && AltoClef.inGame() && mod.getModSettings() != null) {
            if (ticks >= config.startDelayTicks) {
                commandStarted = true;
                writeEvent("command_start", field("command", config.command));
                AltoClef.getCommandExecutor().executeWithPrefix(config.command);
            }
            return;
        }

        if (!commandStarted) {
            return;
        }

        if (ticks % config.sampleIntervalTicks == 0) {
            sample();
        }

        if (config.maxRunSeconds > 0 && secondsSince(startedAtMs) >= config.maxRunSeconds) {
            fail("TIMEOUT", "Maximum run time exceeded.");
        }
    }

    private void tickWorldCreation(MinecraftClient client) {
        if (client.currentScreen instanceof AccessibilityOnboardingScreen screen) {
            dismissAccessibilityOnboarding(client, screen);
            return;
        }

        if (!worldCreateRequested && client.currentScreen instanceof TitleScreen) {
            worldCreateRequested = true;
            writeEvent("world_create_screen_open", field("worldName", config.worldName));
            CreateWorldScreen.show(client, () -> writeEvent("world_create_screen_closed"));
            return;
        }

        if (!worldCreateStarted && client.currentScreen instanceof CreateWorldScreen screen) {
            worldCreateScreenTicks++;
            if (worldCreateScreenTicks < 20) {
                return;
            }

            worldCreateStarted = true;
            try {
                WorldCreator creator = screen.getWorldCreator();
                creator.setWorldName(config.worldName);
                if (!config.seed.isBlank()) {
                    creator.setSeed(config.seed);
                }
                creator.setGameMode(WorldCreator.Mode.SURVIVAL);
                creator.setDifficulty(Difficulty.NORMAL);
                creator.setCheatsEnabled(false);

                ((CreateWorldScreenAccessor) screen).altoclef$createLevel();
                writeEvent("world_create_start", field("worldName", config.worldName));
            } catch (RuntimeException e) {
                fail("WORLD_CREATE_FAILED", e.toString());
            }
        }
    }

    private void dismissAccessibilityOnboarding(MinecraftClient client, AccessibilityOnboardingScreen screen) {
        if (!accessibilityOnboardingDismissed) {
            accessibilityOnboardingDismissed = true;
            writeEvent("accessibility_onboarding_dismiss", field("title", screen.getTitle().getString()));
        }

        client.options.setAccessibilityOnboarded();
        client.options.write();
        client.setScreen(new TitleScreen());
    }

    private void sample() {
        String progressKey = buildProgressKey();
        if (!Objects.equals(progressKey, lastProgressKey)) {
            lastProgressKey = progressKey;
            lastProgressAtMs = System.currentTimeMillis();
        }

        String taskChain = getTaskChain();
        BlockPos pos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        String dimension = getDimensionName();
        double health = mod.getPlayer() == null ? 0 : mod.getPlayer().getHealth();
        int food = mod.getPlayer() == null ? 0 : mod.getPlayer().getHungerManager().getFoodLevel();

        writeEvent("sample",
                field("seconds", secondsSince(startedAtMs)),
                field("dimension", dimension),
                field("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ()),
                field("health", health),
                field("food", food),
                field("tasks", taskChain));

        long stalledSeconds = secondsSince(lastProgressAtMs);
        if (stalledSeconds >= config.stallSeconds) {
            fail("STALL", "No progress for " + stalledSeconds + " seconds. Last state: " + progressKey);
        }
    }

    private String buildProgressKey() {
        BlockPos pos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        return getDimensionName() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + "|" + getTaskChain() + "|" + getInventorySignature();
    }

    private String getTaskChain() {
        TaskChain chain = mod.getTaskRunner().getCurrentTaskChain();
        if (chain == null) {
            return "<no-chain>";
        }

        List<Task> tasks = chain.getTasks();
        if (tasks.isEmpty()) {
            return chain.getName() + ":<empty>";
        }

        StringJoiner joiner = new StringJoiner(" > ");
        for (Task task : tasks) {
            joiner.add(task.toString());
        }
        return chain.getName() + ":" + joiner;
    }

    private String getInventorySignature() {
        if (mod.getPlayer() == null) {
            return "<no-player>";
        }
        return mod.getPlayer().getInventory().getMainStacks().stream()
                .filter(stack -> !stack.isEmpty())
                .map(stack -> stack.getItem().toString() + "x" + stack.getCount())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("<empty>");
    }

    private String getDimensionName() {
        if (mod.getWorld() == null) {
            return "<no-world>";
        }
        RegistryKey<World> key = mod.getWorld().getRegistryKey();
        return key.getValue().toString();
    }

    private void onDebugLog(Debug.LogEntry entry) {
        if (finished) {
            return;
        }

        if (entry.level() == Debug.LogLevel.ERROR) {
            fail("DEBUG_ERROR", entry.message());
            return;
        }

        if (entry.level() == Debug.LogLevel.WARNING && config.repeatedWarningLimit > 0) {
            if (Objects.equals(entry.message(), lastWarning)) {
                repeatedWarningCount++;
            } else {
                lastWarning = entry.message();
                repeatedWarningCount = 1;
            }

            if (repeatedWarningCount >= config.repeatedWarningLimit) {
                fail("REPEATED_WARNING", entry.message());
            }
        }
    }

    private void onTaskFinished(TaskFinishedEvent event) {
        if (finished || !commandStarted) {
            return;
        }

        if (event.lastTaskRan instanceof BeatMinecraft2Task) {
            writeEvent("result",
                    field("status", "success"),
                    field("durationSeconds", event.durationSeconds),
                    field("message", "BeatMinecraft2Task completed."));
            finishAndMaybeStop();
        }
    }

    private void fail(String reason, String message) {
        if (finished) {
            return;
        }

        writeEvent("result",
                field("status", "failure"),
                field("reason", reason),
                field("message", message),
                field("tasks", getTaskChain()));
        Debug.logInternal("[GamerHarness] failure: " + reason + " - " + message);
        finishAndMaybeStop();
    }

    private void finishAndMaybeStop() {
        finished = true;
        Debug.removeLogListener(logListener);
        flushQuietly();
        if (config.stopClientOnResult) {
            MinecraftClient.getInstance().scheduleStop();
        }
    }

    private long secondsSince(long sinceMs) {
        return (System.currentTimeMillis() - sinceMs) / 1000L;
    }

    private JsonField field(String name, String value) {
        return new JsonField(name, value);
    }

    private JsonField field(String name, boolean value) {
        return new JsonField(name, Boolean.toString(value), true);
    }

    private JsonField field(String name, int value) {
        return new JsonField(name, Integer.toString(value), true);
    }

    private JsonField field(String name, long value) {
        return new JsonField(name, Long.toString(value), true);
    }

    private JsonField field(String name, double value) {
        return new JsonField(name, Double.toString(value), true);
    }

    private void writeEvent(String type, JsonField... fields) {
        try {
            writer.write("{\"time\":\"");
            writer.write(escape(Instant.now().toString()));
            writer.write("\",\"type\":\"");
            writer.write(escape(type));
            writer.write("\"");
            for (JsonField field : fields) {
                writer.write(",\"");
                writer.write(escape(field.name));
                writer.write("\":");
                if (field.raw) {
                    writer.write(field.value);
                } else {
                    writer.write("\"");
                    writer.write(escape(field.value));
                    writer.write("\"");
                }
            }
            writer.write("}");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("[GamerHarness] Failed to write event to " + logPath + ": " + e);
        }
    }

    private void flushQuietly() {
        try {
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private record JsonField(String name, String value, boolean raw) {
        private JsonField(String name, String value) {
            this(name, value, false);
        }
    }

    private record Config(
            boolean enabled,
            boolean createWorld,
            String worldName,
            String seed,
            String command,
            int startDelayTicks,
            int sampleIntervalTicks,
            int stallSeconds,
            int maxRunSeconds,
            int repeatedWarningLimit,
            boolean stopClientOnResult
    ) {
        private static Config load() {
            boolean enabled = getBool("ALTOCLEF_GAMER_HARNESS", "altoclef.gamerHarness.enabled", false);
            String defaultWorldName = "Machoclef Harness " + FILE_TIME_FORMAT.format(Instant.now());
            return new Config(
                    enabled,
                    getBool("ALTOCLEF_GAMER_HARNESS_CREATE_WORLD", "altoclef.gamerHarness.createWorld", true),
                    getString("ALTOCLEF_GAMER_HARNESS_WORLD_NAME", "altoclef.gamerHarness.worldName", defaultWorldName),
                    getString("ALTOCLEF_GAMER_HARNESS_SEED", "altoclef.gamerHarness.seed", ""),
                    getString("ALTOCLEF_GAMER_HARNESS_COMMAND", "altoclef.gamerHarness.command", "gamer"),
                    getInt("ALTOCLEF_GAMER_HARNESS_START_DELAY_TICKS", "altoclef.gamerHarness.startDelayTicks", 100),
                    getInt("ALTOCLEF_GAMER_HARNESS_SAMPLE_TICKS", "altoclef.gamerHarness.sampleTicks", 20),
                    getInt("ALTOCLEF_GAMER_HARNESS_STALL_SECONDS", "altoclef.gamerHarness.stallSeconds", 300),
                    getInt("ALTOCLEF_GAMER_HARNESS_MAX_SECONDS", "altoclef.gamerHarness.maxSeconds", 21600),
                    getInt("ALTOCLEF_GAMER_HARNESS_REPEATED_WARNING_LIMIT", "altoclef.gamerHarness.repeatedWarningLimit", 8),
                    getBool("ALTOCLEF_GAMER_HARNESS_STOP_CLIENT", "altoclef.gamerHarness.stopClient", true)
            );
        }

        private static String getString(String envName, String propertyName, String fallback) {
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            String propertyValue = System.getProperty(propertyName);
            if (propertyValue != null && !propertyValue.isBlank()) {
                return propertyValue;
            }
            return fallback;
        }

        private static boolean getBool(String envName, String propertyName, boolean fallback) {
            String value = getString(envName, propertyName, Boolean.toString(fallback));
            return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("1") || value.equalsIgnoreCase("yes");
        }

        private static int getInt(String envName, String propertyName, int fallback) {
            String value = getString(envName, propertyName, Integer.toString(fallback));
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
