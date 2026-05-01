package adris.altoclef.eventbus.events;

public class TitleScreenEntryEvent {
    private static boolean initialized;

    public static boolean markInitialized() {
        if (initialized) {
            return false;
        }
        initialized = true;
        return true;
    }
}
