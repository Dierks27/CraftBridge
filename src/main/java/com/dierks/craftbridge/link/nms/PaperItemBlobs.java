package com.dierks.craftbridge.link.nms;

import com.dierks.craftbridge.link.ItemBlobs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * NMS implementation (Mojang mappings via paperweight-userdev). The names this depends on:
 * <ul>
 *   <li>{@code ItemStack.OPTIONAL_STREAM_CODEC} — the codec vanilla uses for a slot's
 *       contents, so an empty stack is representable and every component travels;</li>
 *   <li>{@code RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)} and
 *       {@code MinecraftServer#registryAccess()} — components are registry-aware;</li>
 *   <li>{@code CraftItemStack#asNMSCopy} / {@code #asBukkitCopy}.</li>
 * </ul>
 * The same three are used by {@code jei.nms.PaperRecipeSyncEncoder}; if one of them moves,
 * both fail to load and both features switch themselves off rather than breaking the server.
 */
public final class PaperItemBlobs implements ItemBlobs {

    @Override
    public byte[] encode(org.bukkit.inventory.ItemStack stack) {
        ByteBuf raw = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registries());
            net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, CraftItemStack.asNMSCopy(stack));
            byte[] out = new byte[raw.readableBytes()];
            raw.readBytes(out);
            return out;
        } finally {
            raw.release();
        }
    }

    @Override
    public org.bukkit.inventory.ItemStack decode(byte[] bytes) {
        ByteBuf raw = Unpooled.wrappedBuffer(bytes);
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registries());
            net.minecraft.world.item.ItemStack decoded =
                    net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            return decoded.isEmpty() ? null : CraftItemStack.asBukkitCopy(decoded);
        } catch (RuntimeException e) {
            return null; // client-written bytes: a bad one is a refused request, not an exception
        } finally {
            raw.release();
        }
    }

    private static net.minecraft.core.RegistryAccess registries() {
        return MinecraftServer.getServer().registryAccess();
    }
}
