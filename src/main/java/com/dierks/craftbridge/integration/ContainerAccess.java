package com.dierks.craftbridge.integration;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lockable;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;
import java.util.List;

/**
 * "May this player use that container?" — shared by sorting and the Linked Workbench.
 *
 * <p>Three layers, cheapest first:
 * <ol>
 *   <li>Vanilla container locks ({@link Lockable#isLocked()}): always honoured.</li>
 *   <li>Towny, via reflection on its stable {@code PlayerCacheUtil} entry point, so the
 *       plugin compiles and runs without Towny on the classpath. Degrades to "allow"
 *       (logged once) if the call fails.</li>
 *   <li>A synthetic {@link PlayerInteractEvent} (right-click on the block) that any
 *       protection plugin — WorldGuard, GriefPrevention, Lockette-style plugins — can
 *       cancel. Our own listeners ignore synthetic events (see {@link #isSynthetic}).</li>
 * </ol>
 */
public final class ContainerAccess {

    private static final ThreadLocal<Boolean> SYNTHETIC = ThreadLocal.withInitial(() -> false);

    private final CraftBridgePlugin plugin;
    private final boolean townyPresent;
    private boolean warnedTowny = false;

    public ContainerAccess(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        PluginManager pm = plugin.getServer().getPluginManager();
        this.townyPresent = pm.getPlugin("Towny") != null;
    }

    /** True while CraftBridge is firing a permission-probe event; listeners must ignore it. */
    public static boolean isSynthetic() {
        return SYNTHETIC.get();
    }

    /** Vanilla lock check only. */
    public boolean isLocked(Block block) {
        BlockState state = block.getState();
        return state instanceof Lockable lockable && lockable.isLocked();
    }

    /**
     * Full check: lock, Towny, then a synthetic interact event other protection plugins
     * can veto. {@code probeWithEvent=false} skips the last layer (use it when the player
     * already has the container open — they were allowed in).
     */
    public boolean canUse(Player player, Block block, boolean probeWithEvent) {
        if (isLocked(block)) {
            return false;
        }
        if (townyPresent && !townyAllows(player, block)) {
            return false;
        }
        if (probeWithEvent && !interactProbeAllows(player, block)) {
            return false;
        }
        return true;
    }

    /**
     * {@link #canUse} for every block behind {@code inventory}: both halves of a double chest,
     * otherwise just {@code block}.
     *
     * <p>A double chest's inventory is both halves at once, so checking only the half that was
     * clicked (or that a scan happened to reach first) let a player sort or pull from a half
     * that is locked, or that sits across a claim border they may not cross. Vanilla itself
     * refuses to open a double chest unless both halves let the player in.
     */
    public boolean canUseAll(Player player, Block block, Inventory inventory, boolean probeWithEvent) {
        for (Block part : blocksBehind(block, inventory)) {
            if (!canUse(player, part, probeWithEvent)) {
                return false;
            }
        }
        return true;
    }

    /** Both halves of a double chest's inventory, or just {@code block} for anything else. */
    public static List<Block> blocksBehind(Block block, Inventory inventory) {
        if (inventory instanceof DoubleChestInventory both) {
            Location left = both.getLeftSide().getLocation();
            Location right = both.getRightSide().getLocation();
            if (left != null && right != null) {
                return List.of(left.getBlock(), right.getBlock());
            }
        }
        return List.of(block);
    }

    private boolean townyAllows(Player player, Block block) {
        try {
            Class<?> playerCacheUtil = Class.forName("com.palmergames.bukkit.towny.utils.PlayerCacheUtil");
            Class<?> actionTypeClass = Class.forName("com.palmergames.bukkit.towny.object.TownyPermission$ActionType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object switchAction = Enum.valueOf((Class<Enum>) actionTypeClass.asSubclass(Enum.class), "SWITCH");
            Material material = block.getType();
            Location location = block.getLocation();
            Method getCachePermission = playerCacheUtil.getMethod(
                    "getCachePermission", Player.class, Location.class, Material.class, actionTypeClass);
            Object result = getCachePermission.invoke(null, player, location, material, switchAction);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            if (!warnedTowny) {
                warnedTowny = true;
                plugin.getLogger().warning("Towny container check unavailable (" + t + "); allowing by default.");
            }
            return true;
        }
    }

    private boolean interactProbeAllows(Player player, Block block) {
        PlayerInteractEvent probe = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND);
        SYNTHETIC.set(true);
        try {
            plugin.getServer().getPluginManager().callEvent(probe);
        } finally {
            SYNTHETIC.set(false);
        }
        return probe.useInteractedBlock() != Event.Result.DENY;
    }
}
