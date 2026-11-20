package pl.mace.tabliczkahome;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tabliczka Home.
 *
 * Receptura (stół rzemieślniczy):
 *   E E E
 *   E S E     E = szmaragd, S = trzeszczące serce (Creaking Heart)
 *   E E E
 *
 * Tabliczka (papier z poświatą):
 *  - nie da się jej postawić,
 *  - jest niezniszczalna jako przedmiot na ziemi (kaktus, ogień, lawa, wybuchy, despawn),
 *  - prawy przycisk myszy otwiera okno home (2 sloty: ustaw / teleport / usuń),
 *  - cooldown teleportu 5 minut, odliczanie 10 sekund żółtym napisem na ekranie.
 */
public final class TabliczkaHome extends JavaPlugin implements Listener {

    private static final int MAX_HOMES = 2;
    private static final int TELEPORT_SECONDS = 10;
    private static final long COOLDOWN_MILLIS = 5 * 60 * 1000L;

    /** Sloty w oknie dla Home 1 i Home 2. */
    private static final int[] HOME_SLOTS = {11, 15};
    private static final int INFO_SLOT = 22;

    private NamespacedKey tabletKey;
    private NamespacedKey recipeKey;

    private File dataFile;
    private YamlConfiguration data;

    /** Aktualne odliczania teleportu. */
    private final Map<UUID, BukkitRunnable> pending = new HashMap<>();
    /** Moment ostatniego teleportu (cooldown trzymany w pamięci - zeruje się po restarcie). */
    private final Map<UUID, Long> lastTeleport = new HashMap<>();

    // ------------------------------------------------------------------ start / stop

    @Override
    public void onEnable() {
        tabletKey = new NamespacedKey(this, "tabliczka_home");
        recipeKey = new NamespacedKey(this, "tabliczka_home_receptura");

        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "homes.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createTablet());
        recipe.shape(
                "EEE",
                "ESE",
                "EEE");
        recipe.setIngredient('E', Material.EMERALD);
        recipe.setIngredient('S', Material.CREAKING_HEART);
        Bukkit.addRecipe(recipe);

        getServer().getPluginManager().registerEvents(this, this);

        var cmd = getCommand("craftinghome");
        if (cmd != null) {
            cmd.setExecutor(this::handleRecipeCommand);
        }
    }

    @Override
    public void onDisable() {
        for (BukkitRunnable task : pending.values()) {
            task.cancel();
        }
        pending.clear();
        if (recipeKey != null) {
            Bukkit.removeRecipe(recipeKey);
        }
    }

    // ------------------------------------------------------------------ tabliczka

    private ItemStack createTablet() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Tabliczka Home", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("Kliknij prawym przyciskiem myszy,", NamedTextColor.GRAY),
                    line("żeby otworzyć menu home.", NamedTextColor.GRAY),
                    line("Niezniszczalna.", NamedTextColor.DARK_PURPLE)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(tabletKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isTablet(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(tabletKey, PersistentDataType.BYTE);
    }

    /** Tabliczka nie może być składnikiem żadnej innej receptury (np. mapy, książki). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isTablet(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ niezniszczalność

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (isTablet(event.getEntity().getItemStack())) {
            event.getEntity().setInvulnerable(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && isTablet(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (isTablet(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ otwieranie menu

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isTablet(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().openInventory(buildHomesGui(event.getPlayer()));
    }

    // ------------------------------------------------------------------ dane home

    private record Home(String world, double x, double y, double z, float yaw, float pitch) {
        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private Home getHome(UUID uuid, int index) {
        String base = "players." + uuid + "." + index;
        String world = data.getString(base + ".world");
        if (world == null) {
            return null;
        }
        return new Home(world,
                data.getDouble(base + ".x"),
                data.getDouble(base + ".y"),
                data.getDouble(base + ".z"),
                (float) data.getDouble(base + ".yaw"),
                (float) data.getDouble(base + ".pitch"));
    }

    private void setHome(UUID uuid, int index, Location loc) {
        String base = "players." + uuid + "." + index;
        data.set(base + ".world", loc.getWorld().getName());
        data.set(base + ".x", loc.getX());
        data.set(base + ".y", loc.getY());
        data.set(base + ".z", loc.getZ());
        data.set(base + ".yaw", (double) loc.getYaw());
        data.set(base + ".pitch", (double) loc.getPitch());
        saveData();
    }

    private void deleteHome(UUID uuid, int index) {
        data.set("players." + uuid + "." + index, null);
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Nie udało się zapisać homes.yml: " + e.getMessage());
        }
    }

    private long cooldownRemainingMillis(UUID uuid) {
        Long last = lastTeleport.get(uuid);
        if (last == null) {
            return 0;
        }
        return Math.max(0, COOLDOWN_MILLIS - (System.currentTimeMillis() - last));
    }

    private static String formatTime(long millis) {
        long seconds = (millis + 999) / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------ okna (GUI)

    private enum GuiType { HOMES, RECIPE }

    /** Holder tylko po to, żeby rozpoznać nasze okna w eventach. */
    private static final class Gui implements InventoryHolder {
        private final GuiType type;
        private Inventory inventory;

        private Gui(GuiType type) {
            this.type = type;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
        });
        return stack;
    }

    private Inventory buildHomesGui(Player player) {
        Gui holder = new Gui(GuiType.HOMES);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Twoje home", NamedTextColor.DARK_GRAY));
        holder.inventory = inv;

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        UUID uuid = player.getUniqueId();
        for (int i = 0; i < MAX_HOMES; i++) {
            int index = i + 1;
            Home home = getHome(uuid, index);
            if (home != null) {
                inv.setItem(HOME_SLOTS[i], item(Material.RED_BED,
                        Component.text("Home " + index, NamedTextColor.GOLD),
                        List.of(
                                line("Świat: " + home.world(), NamedTextColor.GRAY),
                                line("X: " + (int) Math.floor(home.x())
                                        + "  Y: " + (int) Math.floor(home.y())
                                        + "  Z: " + (int) Math.floor(home.z()), NamedTextColor.GRAY),
                                line(" ", NamedTextColor.GRAY),
                                line("Lewy klik: teleportacja", NamedTextColor.GREEN),
                                line("Shift + prawy klik: usuń", NamedTextColor.RED))));
            } else {
                inv.setItem(HOME_SLOTS[i], item(Material.GRAY_BED,
                        Component.text("Home " + index + " - puste", NamedTextColor.GRAY),
                        List.of(
                                line("Lewy klik: ustaw home", NamedTextColor.GREEN),
                                line("w miejscu, w którym stoisz.", NamedTextColor.GREEN))));
            }
        }

        long remaining = cooldownRemainingMillis(uuid);
        inv.setItem(INFO_SLOT, item(Material.CLOCK,
                Component.text("Cooldown teleportu", NamedTextColor.YELLOW),
                List.of(remaining > 0
                        ? line("Poczekaj jeszcze " + formatTime(remaining), NamedTextColor.RED)
                        : line("Gotowe - możesz się teleportować", NamedTextColor.GREEN),
                        line("Cooldown: 5 minut, odliczanie: 10 sekund", NamedTextColor.GRAY))));
        return inv;
    }

    private Inventory buildRecipeGui() {
        Gui holder = new Gui(GuiType.RECIPE);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Receptura: Tabliczka Home", NamedTextColor.DARK_GRAY));
        holder.inventory = inv;

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        int[] emeraldSlots = {1, 2, 3, 10, 12, 19, 20, 21};
        for (int slot : emeraldSlots) {
            inv.setItem(slot, new ItemStack(Material.EMERALD));
        }
        inv.setItem(11, new ItemStack(Material.CREAKING_HEART));
        inv.setItem(14, item(Material.ARROW, Component.text("→", NamedTextColor.GRAY), List.of()));
        inv.setItem(16, createTablet());
        return inv;
    }

    private boolean handleRecipeCommand(@NotNull CommandSender sender, @NotNull Command command,
                                        @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tej komendy może użyć tylko gracz.");
            return true;
        }
        player.openInventory(buildRecipeGui());
        return true;
    }

    // ------------------------------------------------------------------ klikanie w oknie

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        // nic nie wolno wyjmować ani wkładać
        event.setCancelled(true);

        if (gui.type != GuiType.HOMES) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int index = slot == HOME_SLOTS[0] ? 1 : (slot == HOME_SLOTS[1] ? 2 : 0);
        if (index == 0) {
            return;
        }
        handleHomeClick(player, index, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
        }
    }

    private void handleHomeClick(Player player, int index, ClickType click) {
        UUID uuid = player.getUniqueId();
        Home home = getHome(uuid, index);

        if (home == null) {
            if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
                setHome(uuid, index, player.getLocation());
                player.sendMessage(Component.text("Ustawiono Home " + index + " w tym miejscu.", NamedTextColor.GREEN));
                refreshGui(player);
            }
            return;
        }

        if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
            startTeleport(player, index, home);
        } else if (click == ClickType.SHIFT_RIGHT) {
            deleteHome(uuid, index);
            player.sendMessage(Component.text("Usunięto Home " + index + ".", NamedTextColor.YELLOW));
            refreshGui(player);
        } else {
            player.sendMessage(Component.text(
                    "Lewy klik: teleportacja. Shift + prawy klik: usunięcie.", NamedTextColor.GRAY));
        }
    }

    /** Odświeża okno w następnym ticku (bezpieczniej niż w trakcie kliknięcia). */
    private void refreshGui(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.openInventory(buildHomesGui(player));
            }
        });
    }

    // ------------------------------------------------------------------ teleportacja

    private void startTeleport(Player player, int index, Home home) {
        UUID uuid = player.getUniqueId();

        if (pending.containsKey(uuid)) {
            player.sendMessage(Component.text("Teleportacja już trwa.", NamedTextColor.RED));
            return;
        }

        long remaining = cooldownRemainingMillis(uuid);
        if (remaining > 0) {
            player.sendMessage(Component.text(
                    "Cooldown! Możesz się teleportować za " + formatTime(remaining) + ".", NamedTextColor.RED));
            return;
        }

        Location target = home.toLocation();
        if (target == null) {
            player.sendMessage(Component.text("Świat tego home nie jest załadowany.", NamedTextColor.RED));
            return;
        }

        player.closeInventory();

        BukkitRunnable task = new BukkitRunnable() {
            private int left = TELEPORT_SECONDS;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    pending.remove(uuid);
                    cancel();
                    return;
                }

                if (left <= 0) {
                    pending.remove(uuid);
                    cancel();
                    lastTeleport.put(uuid, System.currentTimeMillis());
                    player.teleportAsync(target);
                    player.sendActionBar(Component.text("Przeteleportowano do Home " + index + ".", NamedTextColor.GREEN));
                    return;
                }

                // duży żółty napis na ekranie
                player.showTitle(Title.title(
                        Component.text("Teleportacja za " + left + "s", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.text("Home " + index, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
                left--;
            }
        };
        pending.put(uuid, task);
        task.runTaskTimer(this, 0L, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BukkitRunnable task = pending.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
