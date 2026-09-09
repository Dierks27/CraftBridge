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
 *       exposes the {@code recipes} RecipeMap. This is the same collection plugin recipes
 *       land in: Paper's {@code RecipeMap#addRecipe} (what {@code Bukkit.addRecipe} ends up
 *       calling through {@code CraftRecipe#addToRecipeManager} and
 *       {@code RecipeManager#addRecipe}) puts the holder in both {@code byKey} — which
 *       {@code values()} returns — and {@code byType}, which CraftBukkit's
 *       {@code RecipeIterator} walks. Iterating either one sees plugin recipes.</li>
 *   <li>{@code RecipeHolder#id()} / {@code #value()} / {@code #toBukkitRecipe()},
 *       {@code Recipe#getSerializer()}, {@code RecipeSerializer#streamCodec()} (deprecated in
 *       vanilla, used by Fabric's sync).</li>
 *   <li>{@code RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)}, {@code writeVarInt},
 *       {@code writeIdentifier}, {@code writeResourceKey}/{@code readResourceKey} — exactly
 *       what {@code net.fabricmc.fabric.impl.recipe.sync.ClientboundRecipeSyncPayload}
 *       reads and writes.</li>
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
        Map<String, Integer> byNamespace = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
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
            // The client decodes the whole payload in one pass and aborts all of it on the
            // first recipe that throws, so verify each one round-trips before including it.
            String failure = verify(server, serializer, holder);
            if (failure != null) {
                problems.add(holder.id().identifier() + ": " + failure);
                continue;
            }
            bySerializer.computeIfAbsent(serializer, k -> new ArrayList<>()).add(holder);
            byNamespace.merge(holder.id().identifier().getNamespace(), 1, Integer::sum);
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
                StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec = codecOf(serializer);
                for (RecipeHolder<?> holder : entry.getValue()) {
                    buf.writeResourceKey(holder.id());
                    codec.encode(buf, holder.value());
                    count++;
                }
            }
            byte[] out = new byte[raw.readableBytes()];
            raw.readBytes(out);
            return new Encoded(out, count, byNamespace, problems);
        } finally {
            raw.release();
        }
    }

    /** Encode one recipe and read it straight back; null when it survives, else why not. */
    private static String verify(MinecraftServer server, RecipeSerializer<?> serializer, RecipeHolder<?> holder) {
        ByteBuf scratch = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(scratch, server.registryAccess());
            StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec = codecOf(serializer);
            codec.encode(buf, holder.value());
            codec.decode(buf);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            scratch.release();
        }
    }

    @Override
    public RoundTrip roundTrip(String recipeKey) {
        MinecraftServer server = MinecraftServer.getServer();
        RecipeManager recipeManager = server.getRecipeManager();
        RecipeHolder<?> found = null;
        for (RecipeHolder<?> holder : recipeManager.recipes.values()) {
            if (holder.id().identifier().toString().equals(recipeKey)) {
                found = holder;
                break;
            }
        }
        if (found == null) {
            return new RoundTrip(-1, null, "no recipe with that key on this server");
        }
        RecipeSerializer<?> serializer = found.value().getSerializer();
        if (BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer) == null) {
            return new RoundTrip(-1, null, "its serializer is not registered, so it is never sent");
        }
        ByteBuf scratch = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(scratch, server.registryAccess());
            StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codec = codecOf(serializer);
            codec.encode(buf, found.value());
            int bytes = buf.readableBytes();
            Recipe<?> decoded = codec.decode(buf);
            return new RoundTrip(bytes, new RecipeHolder<>(found.id(), decoded).toBukkitRecipe(), null);
        } catch (Throwable t) {
            return new RoundTrip(-1, null, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            scratch.release();
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static StreamCodec<RegistryFriendlyByteBuf, Recipe<?>> codecOf(RecipeSerializer<?> serializer) {
        return (StreamCodec<RegistryFriendlyByteBuf, Recipe<?>>) serializer.streamCodec();
    }

    @Override
    public int liveRecipeCount() {
        return MinecraftServer.getServer().getRecipeManager().recipes.values().size();
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
