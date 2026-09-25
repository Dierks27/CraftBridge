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
 *   <li>{@code CraftItemStack#asNMSCopy} / {@code #asBukkitCopy(ItemInstance)}.</li>
 * </ul>
 * The same three are used by {@code jei.nms.PaperRecipeSyncEncoder}. A class that is missing
 * outright fails to load and switches its feature off, but a method that moved only fails when
 * it is first called — the JVM resolves method references lazily — so a startup log that looks
 * clean proves nothing about the calls here.
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
            // Through the ItemInstance overload, the only one Paper 26.3 kept: 26.3 removed
            // asBukkitCopy(net.minecraft.world.item.ItemStack), which a jar built against 26.2
            // binds to by default and which then throws NoSuchMethodError on the first pull.
            // 26.2 has this overload too, and on both it copies the stack before mirroring it.
            return decoded.isEmpty() ? null
                    : CraftItemStack.asBukkitCopy((net.minecraft.world.item.ItemInstance) decoded);
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
