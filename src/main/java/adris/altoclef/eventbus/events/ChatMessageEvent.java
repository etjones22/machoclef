package adris.altoclef.eventbus.events;

import net.minecraft.text.Text;

/**
 * Whenever chat appears
 */
public class ChatMessageEvent {
    public Text message;

    public ChatMessageEvent(Text message) {
        this.message = message;
    }
}
