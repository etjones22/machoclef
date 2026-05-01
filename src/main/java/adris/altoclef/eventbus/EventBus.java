package adris.altoclef.eventbus;

import net.minecraft.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A static class to solve dependency issues. Lets us send and receive events globally, decoupling our codebase.
 *
 * Technically `ConfigHelper` does something like this, but here is a more general case.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private static int _publishDepth;

    private static final HashMap<Class, List<Subscription>> _topics = new HashMap<>();
    private static final List<Pair<Class, Subscription>> _toAdd = new ArrayList<>();

    public static <T> void publish(T event) {
        Class type = event.getClass();

        if (_publishDepth == 0) {
            flushQueuedSubscriptions();
        }

        if (_topics.containsKey(type)) {
            List<Subscription> subscribers = new ArrayList<>(_topics.get(type));

            // Subscriptions can be deleted while they're called
            List<Subscription> toDelete = new ArrayList<>();

            // Go through our subscription list. We shouldn't modify the list while we're iterating it.
            _publishDepth++;
            try {
                for (Subscription subRaw : subscribers) {
                    Subscription<T> sub;
                    try {
                        sub = (Subscription<T>) subRaw;
                        if (sub.shouldDelete()) {
                            toDelete.add(sub);
                        } else {
                            sub.accept(event);
                        }
                    } catch (ClassCastException e) {
                        System.err.println("TRIED PUBLISHING MISMAPPED EVENT: " + event);
                        e.printStackTrace();
                    }
                }
            } finally {
                _publishDepth--;
                if (!toDelete.isEmpty()) {
                    _topics.get(type).removeAll(toDelete);
                }
                if (_publishDepth == 0) {
                    flushQueuedSubscriptions();
                }
            }
        }
    }

    private static void flushQueuedSubscriptions() {
        for (Pair<Class, Subscription> toAdd : _toAdd) {
            subscribeInternal(toAdd.getLeft(), toAdd.getRight());
        }
        _toAdd.clear();
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        if (!_topics.containsKey(type)) {
            _topics.put(type, new ArrayList<>());
        }
        _topics.get(type).add(sub);
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> consumeEvent) {
        Subscription<T> sub = new Subscription<>(consumeEvent);
        if (_publishDepth > 0) {
            _toAdd.add(new Pair<>(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    public static <T> void unsubscribe(Subscription<T> subscription) {
        if (subscription != null)
            subscription.delete();
    }
}
