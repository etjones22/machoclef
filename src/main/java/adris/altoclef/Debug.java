package adris.altoclef;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// TODO: Debug library or use Minecraft's built in debugger
public class Debug {

    public enum LogLevel {
        INTERNAL,
        MESSAGE,
        WARNING,
        ERROR
    }

    public record LogEntry(LogLevel level, String message) {
    }

    private static final List<Consumer<LogEntry>> LOG_LISTENERS = new CopyOnWriteArrayList<>();

    public static void addLogListener(Consumer<LogEntry> listener) {
        LOG_LISTENERS.add(listener);
    }

    public static void removeLogListener(Consumer<LogEntry> listener) {
        LOG_LISTENERS.remove(listener);
    }

    private static void notifyLogListeners(LogLevel level, String message) {
        LogEntry entry = new LogEntry(level, message);
        for (Consumer<LogEntry> listener : LOG_LISTENERS) {
            listener.accept(entry);
        }
    }

    public static void logInternal(String message) {
        System.out.println("ALTO CLEF: " + message);
        notifyLogListeners(LogLevel.INTERNAL, message);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    public static AltoClef jankModInstance;

    private static String getLogPrefix() {
        if (jankModInstance != null) {
            return jankModInstance.getModSettings().getChatLogPrefix();
        }
        return "[Alto Clef] ";
    }

    public static void logMessage(String message, boolean prefix) {
        notifyLogListeners(LogLevel.MESSAGE, message);
        if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
            if (prefix) {
                message = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7r" + message;
            }
            MinecraftClient.getInstance().player.sendMessage(Text.of(message), false);
            //MinecraftClient.getInstance().player.sendChatMessage(msg);
        } else {
            logInternal(message);
        }
    }

    public static void logMessage(String message) {
        logMessage(message, true);
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    public static void logWarning(String message) {
        logInternal("WARNING: " + message);
        notifyLogListeners(LogLevel.WARNING, message);
        if (jankModInstance != null && !jankModInstance.getModSettings().shouldHideAllWarningLogs()) {
            if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
                String msg = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7c" + message + "\u00A7r";
                MinecraftClient.getInstance().player.sendMessage(Text.of(msg), false);
                //MinecraftClient.getInstance().player.sendChatMessage(msg);
            }
        }
    }

    public static void logWarning(String format, Object... args) {
        logWarning(String.format(format, args));
    }

    public static void logError(String message) {
        String stacktrace = getStack(2);
        System.err.println(message);
        System.err.println("at:");
        System.err.println(stacktrace);
        notifyLogListeners(LogLevel.ERROR, message + "\nat:\n" + stacktrace);
        if (MinecraftClient.getInstance() != null && MinecraftClient.getInstance().player != null) {
            String msg = "\u00A72\u00A7l\u00A7c" + getLogPrefix() + "[ERROR] " + message + "\nat:\n" + stacktrace + "\u00A7r";
            MinecraftClient.getInstance().player.sendMessage(Text.of(msg), false);
        }
    }

    public static void logError(String format, Object... args) {
        logError(String.format(format, args));
    }

    public static void logStack() {
        logInternal("STACKTRACE: \n" + getStack(2));
    }

    private static String getStack(int toSkip) {
        StringBuilder stacktrace = new StringBuilder();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (toSkip-- <= 0) {
                stacktrace.append(ste.toString()).append("\n");
            }
        }
        return stacktrace.toString();
    }
}
