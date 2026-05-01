package adris.altoclef.eventbus.events;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public class ClientRenderEvent {
    public DrawContext context;
    public RenderTickCounter tickCounter;

    public ClientRenderEvent(DrawContext context, RenderTickCounter tickCounter) {
        this.context = context;
        this.tickCounter = tickCounter;
    }
}
