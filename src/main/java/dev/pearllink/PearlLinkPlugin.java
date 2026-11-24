package dev.pearllink;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * PearlLink - two special fishing rods (both given with /pearlrod give):
 *
 * Pearl Rod:   sneak + right-click while aiming at YOUR thrown ender pearl to link it.
 *              Right-click to pull it. ANYONE holding the rod can pull it, but the
 *              player who threw the pearl is the one who gets teleported.
 *
 * Orbital Rod: sneak + right-click while aiming at a player to select them.
 *              Right-click to pull. The selected player is teleported to their own pearl.
 */
public final class PearlLinkPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String TYPE_PEARL = "pearl";
    private static final String TYPE_ORBITAL = "orbital";
    private static final String ADMIN_PERMISSION = "pearllink.admin";

    /** How far you can aim to link a pearl / select a player. */
    private static final double RANGE = 64.0;
    /** Aim tolerance in blocks, so a tiny pearl is easy to hit. */
    private static final double RAY_SIZE = 0.6;
    private static final long COOLDOWN_NANOS = 300_000_000L;

    private NamespacedKey typeKey;
    private NamespacedKey pearlKey;
    private NamespacedKey throwerKey;
    private NamespacedKey throwerNameKey;
    private NamespacedKey targetKey;
    private NamespacedKey targetNameKey;

    private final Map<UUID, Long> lastUse = new HashMap<>();

    @Override
    public void onEnable() {
        typeKey = new NamespacedKey(this, "rod_type");
        pearlKey = new NamespacedKey(this, "pearl_uuid");
        throwerKey = new NamespacedKey(this, "thrower_uuid");
        throwerNameKey = new NamespacedKey(this, "thrower_name");
        targetKey = new NamespacedKey(this, "target_uuid");
        targetNameKey = new NamespacedKey(this, "target_name");

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("pearlrod") != null) {
            getCommand("pearlrod").setExecutor(this);
            getCommand("pearlrod").setTabCompleter(this);
        } else {
            getLogger().severe("Command /pearlrod is missing from plugin.yml!");
        }
        getLogger().info("PearlLink enabled. Use /pearlrod give to get a rod.");
    }

    // ------------------------------------------------------------------
    // Rod usage
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        EquipmentSlot hand = event.getHand();
        if (hand == null) return;

        Player player = event.getPlayer();
        ItemStack rod = player.getInventory().getItem(hand);
        String type = rodType(rod);
        if (type == null) return;

        // This is one of our rods: never cast the fishing line with it.
        event.setCancelled(true);

        if (onCooldown(player)) return;

        if (TYPE_PEARL.equals(type)) {
            if (player.isSneaking()) {
                linkPearl(player, hand, rod);
            } else {
                pullWithPearlRod(player, hand, rod);
            }
        } else if (TYPE_ORBITAL.equals(type)) {
            if (player.isSneaking()) {
                selectPlayer(player, hand, rod);
            } else {
                pullWithOrbitalRod(player, rod);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ---- Pearl Rod ----

    private void linkPearl(Player player, EquipmentSlot hand, ItemStack rod) {
        Entity hit = rayTrace(player, e -> e instanceof EnderPearl);
        if (!(hit instanceof EnderPearl pearl)) {
            msg(player, "No ender pearl in sight. Sneak and aim right at a thrown pearl.", NamedTextColor.RED);
            return;
        }

        UUID owner = ownerOf(pearl);
        if (owner == null) {
            msg(player, "That pearl was not thrown by a player.", NamedTextColor.RED);
            return;
        }
        if (!owner.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
            msg(player, "You can only link pearls that you threw yourself.", NamedTextColor.RED);
            return;
        }

        Player thrower = Bukkit.getPlayer(owner);
        String throwerName = thrower != null ? thrower.getName() : "unknown";

        update(player, hand, rod, pdc -> {
            pdc.set(pearlKey, PersistentDataType.STRING, pearl.getUniqueId().toString());
            pdc.set(throwerKey, PersistentDataType.STRING, owner.toString());
            pdc.set(throwerNameKey, PersistentDataType.STRING, throwerName);
        });
        msg(player, "Rod linked to " + throwerName + "'s pearl. Anyone holding it can pull it.", NamedTextColor.GREEN);
    }

    private void pullWithPearlRod(Player activator, EquipmentSlot hand, ItemStack rod) {
        PersistentDataContainer pdc = rod.getItemMeta().getPersistentDataContainer();
        String pearlText = pdc.get(pearlKey, PersistentDataType.STRING);
        String throwerText = pdc.get(throwerKey, PersistentDataType.STRING);
        if (pearlText == null || throwerText == null) {
            msg(activator, "This rod isn't linked. Sneak + right-click while aiming at your pearl.", NamedTextColor.YELLOW);
            return;
        }

        UUID pearlId;
        UUID throwerId;
        try {
            pearlId = UUID.fromString(pearlText);
            throwerId = UUID.fromString(throwerText);
        } catch (IllegalArgumentException ex) {
            update(activator, hand, rod, this::clearPearlLink);
            msg(activator, "The saved link was corrupted and has been cleared. Link the rod again.", NamedTextColor.RED);
            return;
        }

        Entity found = Bukkit.getEntity(pearlId);
        if (!(found instanceof EnderPearl pearl) || !pearl.isValid()) {
            msg(activator, "That pearl isn't loaded or no longer exists.", NamedTextColor.RED);
            return;
        }

        Player thrower = Bukkit.getPlayer(throwerId);
        if (thrower == null) {
            msg(activator, "The player who threw that pearl is offline.", NamedTextColor.RED);
            return;
        }

        if (pullPlayerToPearl(activator, thrower, pearl)) {
            update(activator, hand, rod, this::clearPearlLink);
        }
    }

    // ---- Orbital Rod ----

    private void selectPlayer(Player player, EquipmentSlot hand, ItemStack rod) {
        UUID self = player.getUniqueId();
        Entity hit = rayTrace(player, e -> e instanceof Player p && !p.getUniqueId().equals(self));
        if (!(hit instanceof Player target)) {
            msg(player, "No player in sight. Sneak and aim at the player you want to select.", NamedTextColor.RED);
            return;
        }

        update(player, hand, rod, pdc -> {
            pdc.set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
            pdc.set(targetNameKey, PersistentDataType.STRING, target.getName());
        });
        msg(player, "Selected " + target.getName() + ". Right-click to pull their pearl.", NamedTextColor.GREEN);
    }

    private void pullWithOrbitalRod(Player activator, ItemStack rod) {
        PersistentDataContainer pdc = rod.getItemMeta().getPersistentDataContainer();
        String targetText = pdc.get(targetKey, PersistentDataType.STRING);
        if (targetText == null) {
            msg(activator, "No player selected. Sneak + right-click a player first.", NamedTextColor.YELLOW);
            return;
        }

        UUID targetId;
        try {
            targetId = UUID.fromString(targetText);
        } catch (IllegalArgumentException ex) {
            msg(activator, "The saved target was corrupted. Select a player again.", NamedTextColor.RED);
            return;
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            msg(activator, "The selected player is offline.", NamedTextColor.RED);
            return;
        }

        EnderPearl pearl = findPearlOf(target);
        if (pearl == null) {
            msg(activator, target.getName() + " has no loaded ender pearl in their world.", NamedTextColor.RED);
            return;
        }

        pullPlayerToPearl(activator, target, pearl);
    }

    /** The newest loaded pearl thrown by this player, in the world they are currently in. */
    private EnderPearl findPearlOf(Player owner) {
        EnderPearl best = null;
        for (EnderPearl candidate : owner.getWorld().getEntitiesByClass(EnderPearl.class)) {
            if (!candidate.isValid()) continue;
            UUID candidateOwner = ownerOf(candidate);
            if (candidateOwner == null || !candidateOwner.equals(owner.getUniqueId())) continue;
            if (best == null || candidate.getTicksLived() < best.getTicksLived()) {
                best = candidate;
            }
        }
        return best;
    }

    // ---- Shared ----

    /** Teleports victim to the pearl and removes it. Returns true on success. */
    private boolean pullPlayerToPearl(Player activator, Player victim, EnderPearl pearl) {
        if (!pearl.getWorld().equals(victim.getWorld())) {
            msg(activator, victim.getName() + " is in a different dimension than the pearl.", NamedTextColor.RED);
            return false;
        }

        Location destination = pearl.getLocation().clone();
        Location current = victim.getLocation();
        destination.setYaw(current.getYaw());
        destination.setPitch(current.getPitch());

        if (!victim.teleport(destination)) {
            msg(activator, "The teleport was blocked.", NamedTextColor.RED);
            return false;
        }
        pearl.remove();

        if (activator.getUniqueId().equals(victim.getUniqueId())) {
            msg(activator, "Pulled!", NamedTextColor.GREEN);
        } else {
            msg(activator, "Pulled! " + victim.getName() + " was teleported to their pearl.", NamedTextColor.GREEN);
            msg(victim, activator.getName() + " pulled your pearl.", NamedTextColor.YELLOW);
        }
        return true;
    }

    private UUID ownerOf(EnderPearl pearl) {
        ProjectileSource source = pearl.getShooter();
        if (source instanceof Player shooter) {
            return shooter.getUniqueId();
        }
        return null;
    }

    /**
     * Server-side aim check. A thrown pearl can't be targeted by a normal entity click,
     * so we ray-trace along the player's view direction instead.
     */
    private Entity rayTrace(Player player, Predicate<Entity> filter) {
        Location eye = player.getEyeLocation();
        RayTraceResult result = player.getWorld().rayTraceEntities(eye, eye.getDirection(), RANGE, RAY_SIZE, filter);
        return result == null ? null : result.getHitEntity();
    }

    private void clearPearlLink(PersistentDataContainer pdc) {
        pdc.remove(pearlKey);
        pdc.remove(throwerKey);
        pdc.remove(throwerNameKey);
    }

    private String rodType(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    /** Edits the rod's data, refreshes its name/lore and puts it back in the same hand. */
    private void update(Player player, EquipmentSlot hand, ItemStack rod, Consumer<PersistentDataContainer> change) {
        ItemStack copy = rod.clone();
        copy.editMeta(meta -> {
            change.accept(meta.getPersistentDataContainer());
            refreshDisplay(meta);
        });
        player.getInventory().setItem(hand, copy);
    }

    private void refreshDisplay(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        List<Component> lore = new ArrayList<>();

        if (TYPE_ORBITAL.equals(type)) {
            meta.displayName(plain("Orbital Rod", NamedTextColor.LIGHT_PURPLE));
            String name = pdc.get(targetNameKey, PersistentDataType.STRING);
            lore.add(plain(name == null ? "No player selected" : "Target: " + name, NamedTextColor.WHITE));
            lore.add(plain("Sneak + right-click a player to select", NamedTextColor.GRAY));
            lore.add(plain("Right-click to pull their pearl", NamedTextColor.GRAY));
        } else {
            meta.displayName(plain("Pearl Rod", NamedTextColor.AQUA));
            boolean linked = pdc.has(pearlKey, PersistentDataType.STRING);
            String name = pdc.get(throwerNameKey, PersistentDataType.STRING);
            lore.add(plain(linked ? "Linked to " + name + "'s pearl" : "Not linked", NamedTextColor.WHITE));
            lore.add(plain("Sneak + right-click your pearl to link", NamedTextColor.GRAY));
            lore.add(plain("Right-click to pull (anyone can use it)", NamedTextColor.GRAY));
        }
        meta.lore(lore);
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private void msg(CommandSender target, String text, NamedTextColor color) {
        target.sendMessage(Component.text("PearlLink: " + text, color));
    }

    private boolean onCooldown(Player player) {
        long now = System.nanoTime();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_NANOS) return true;
        lastUse.put(player.getUniqueId(), now);
        return false;
    }

    // ------------------------------------------------------------------
    // /pearlrod give [pearl|orbital] [player]
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            msg(sender, "You don't have permission to use this command.", NamedTextColor.RED);
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("give")) {
            msg(sender, "Usage: /" + label + " give [pearl|orbital] [player]", NamedTextColor.YELLOW);
            return true;
        }

        int index = 1;
        String type = TYPE_PEARL;
        if (args.length > index) {
            String kind = args[index].toLowerCase(Locale.ROOT);
            if (kind.equals("pearl")) {
                index++;
            } else if (kind.equals("orbital")) {
                type = TYPE_ORBITAL;
                index++;
            }
        }

        Player target;
        if (args.length > index) {
            target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                msg(sender, "Player '" + args[index] + "' is not online.", NamedTextColor.RED);
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            msg(sender, "From the console you must name a player.", NamedTextColor.RED);
            return true;
        }

        ItemStack rod = createRod(type);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(rod);
        for (ItemStack extra : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), extra);
        }

        String niceName = type.equals(TYPE_ORBITAL) ? "Orbital Rod" : "Pearl Rod";
        msg(sender, "Gave a " + niceName + " to " + target.getName() + ".", NamedTextColor.GREEN);
        return true;
    }

    private ItemStack createRod(String type) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        rod.editMeta(meta -> {
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
            refreshDisplay(meta);
        });
        return rod;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (!sender.hasPermission(ADMIN_PERMISSION)) return options;

        if (args.length == 1) {
            options.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            options.add("pearl");
            options.add("orbital");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
