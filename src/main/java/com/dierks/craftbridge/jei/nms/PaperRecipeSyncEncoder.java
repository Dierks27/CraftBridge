package com.dierks.craftbridge.jei.nms;

import com.dierks.craftbridge.jei.RecipeSyncEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NMS implementation (Mojang mappings via paperweight-userdev). This is the only class
 * in the plugin that touches server internals; the names it depends on and where they
 * were verified:
 * <ul>
 *   <li>{@code MinecraftServer.getServer().getRecipeManager().recipes.values()} — Paper AT
 *       exposes the {@code recipes} RecipeMap (used by CraftBukkit's RecipeIterator).</li>
 *   <li>{@code RecipeHolder#id()} / {@code #value()}, {@code Recipe#getSerializer()},
 *       {@code RecipeSerializer#streamCodec()} (deprecated in vanilla, used by Fabric's sync).</li>
 *   <li>{@code RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)}, {@code writeVarInt},
 *       {@code writeIdentifier}, {@code writeResourceKey} — exactly what
 *       {@code net.fabricmc.fabric.impl.recipe.sync.ClientboundRecipeSyncPayload} writes.</li>
 *   <li>{@code new ClientboundUpdateRecipesPacket(getSynchronizedItemProperties(), getSynchronizedStonecutterRecipes())}
 *       + {@code ServerRecipeBook#sendInitialRecipeBook} — what Paper's {@code PlayerList#reloadRecipes} does.</li>
 * </ul>
 * If a 26.x build renames any of these, this class fails to load and the feature logs
 * one WARN and disables itself; nothing else in the plugin is affected.
 */
public final class PaperRecipeSyncEncoder implements RecipeSyncEncoder {

    @Override
    public Encoded encode(Set<String> recipeTypeIds) {
        MinecraftServer server = MinecraftServer.getServer();
        RecipeManager recipeManager = server.getRecipeManager();

        Map<RecipeSerializer<?>, List<RecipeHolder<?>>> bySerializer = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : recipeManager.recipes.values()) {
            Recipe<?> recipe = holder.value();
            if (recipeTypeIds != null) {
                Identifier typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
                if (typeId == null || !recipeTypeIds.contains(typeId.toString())) {
                    continue;
                }
            }
            RecipeSerializer<?> serializer = recipe.getSerializer();
            if (BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer) == null) {
                continue; // unregistered serializer: the client could never decode it
            }
            bySerializer.computeIfAbsent(serializer, k -> new ArrayList<>()).add(holder);
        }

        ByteBuf raw = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, server.registryAccess());
            buf.writeVarInt(bySerializer.size());
            int count = 0;
            for (Map.Entry<RecipeSerializer<?>, List<RecipeHolder<?>>> entry : bySerializer.entrySet()) {
                RecipeSerializer<?> serializer = entry.getKey();
                buf.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer));
                buf.writeVarInt(entry.getValue().size());
                @SuppressWarnings({"unchecked", "deprecation"})
                StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec =
                        (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) serializer.streamCodec();
                for (RecipeHolder<?> holder : entry.getValue()) {
                    buf.writeResourceKey(holder.id());
                    codec.encode(buf, holder.value());
                    count++;
                }
            }
            byte[] out = new byte[raw.readableBytes()];
            raw.readBytes(out);
            return new Encoded(out, count);
        } finally {
            raw.release();
        }
    }

    @Override
    public void resendRecipes(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        RecipeManager recipeManager = MinecraftServer.getServer().getRecipeManager();
        handle.connection.send(new ClientboundUpdateRecipesPacket(
                recipeManager.getSynchronizedItemProperties(), recipeManager.getSynchronizedStonecutterRecipes()));
        handle.getRecipeBook().sendInitialRecipeBook(handle);
    }
}
