package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public final class ChatReadMixin {

    @Inject(
            method = "onGameMessage",
            at = @At("HEAD")
    )
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        publish(packet.content());
    }

    @Inject(
            method = "onProfilelessChatMessage",
            at = @At("HEAD")
    )
    private void onProfilelessChatMessage(ProfilelessChatMessageS2CPacket packet, CallbackInfo ci) {
        publish(packet.message());
    }

    private void publish(Text message) {
        EventBus.publish(new ChatMessageEvent(message));
    }
}
