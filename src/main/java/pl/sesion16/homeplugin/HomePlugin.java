package pl.sesion16.homeplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class HomePlugin extends JavaPlugin implements Listener, CommandExecutor {

    private File homesFile;
    private FileConfiguration homesConfig;
    private final Map<UUID, TeleportSession> activeTeleports = new HashMap<>();
    private final Set<UUID> pendingHomeCreation = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createHomesConfig();

        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("sethome")).setExecutor(this);
        Objects.requireNonNull(getCommand("home")).setExecutor(this);
        Objects.requireNonNull(getCommand("delhome")).setExecutor(this);
    }

    @Override
    public void onDisable() {
        for (TeleportSession session : activeTeleports.values()) {
            session.cancelTask();
        }
        activeTeleports.clear();
        pendingHomeCreation.clear();
    }

    private void createHomesConfig() {
        homesFile = new File(getDataFolder(), "homes.yml");
        if (!homesFile.exists()) {
            homesFile.getParentFile().mkdirs();
            try {
                homesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Nie udało się utworzyć pliku homes.yml!");
            }
        }
        homesConfig = YamlConfiguration.loadConfiguration(homesFile);
    }

    private void saveHomesConfig() {
        try {
            homesConfig.save(homesFile);
        } catch (IOException e) {
            getLogger().severe("Nie udało się zapisać pliku homes.yml!");
        }
    }

    private Component color(String text) {
        if (text == null) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private String getPrefixedMessage(String path) {
        String prefix = getConfig().getString("messages.prefix", "&8[&aHomePlugin&8] ");
        String msg = getConfig().getString("messages." + path, "");
        return prefix + msg;
    }

    private String cleanHomeName(String input) {
        if (input == null) return "";
        String clean = input.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
        clean = clean.replaceAll("[^a-zA-Z0-9_-]", "").trim();
        return clean;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getConfig().getString("messages.player-only", "Tylko dla graczy!")));
            return true;
        }

        int maxHomes = getConfig().getInt("settings.max-homes", 3);

        if (command.getName().equalsIgnoreCase("sethome")) {
            Map<String, Location> homes = getPlayerHomes(player.getUniqueId());
            if (homes.size() >= maxHomes) {
                String msg = getPrefixedMessage("limit-reached").replace("%max%", String.valueOf(maxHomes));
                player.sendMessage(color(msg));
                return true;
            }

            if (args.length > 0) {
                String homeName = cleanHomeName(args[0]);
                if (homeName.isEmpty()) {
                    player.sendMessage(color(getPrefixedMessage("invalid-name")));
                    return true;
                }

                saveHome(player.getUniqueId(), homeName, player.getLocation());
                String title = getConfig().getString("titles.home-set.title", "&aHome Ustawiony!").replace("%home%", homeName);
                String subtitle = getConfig().getString("titles.home-set.subtitle", "&7Nazwa: &e%home%").replace("%home%", homeName);
                showTitle(player, title, subtitle);

                String msg = getPrefixedMessage("home-set-success").replace("%home%", homeName);
                player.sendMessage(color(msg));
                return true;
            }

            pendingHomeCreation.add(player.getUniqueId());
            player.sendMessage(color(getPrefixedMessage("prompt-name")));
            return true;

        } else if (command.getName().equalsIgnoreCase("home")) {
            Map<String, Location> homes = getPlayerHomes(player.getUniqueId());
            if (homes.isEmpty()) {
                player.sendMessage(color(getPrefixedMessage("no-homes")));
                return true;
            }

            String guiTitle = getConfig().getString("gui.home-selector-title", "&8Wybierz swój Home");
            Inventory gui = Bukkit.createInventory(null, 9, color(guiTitle));

            int delay = getConfig().getInt("settings.teleport-delay", 10);
            String nameFormat = getConfig().getString("gui.bed-item-name", "&a%home_name%");
            List<String> loreFormat = getConfig().getStringList("gui.bed-item-lore");

            for (String homeName : homes.keySet()) {
                ItemStack bed = new ItemStack(Material.RED_BED);
                ItemMeta meta = bed.getItemMeta();
                if (meta != null) {
                    meta.displayName(color(nameFormat.replace("%home_name%", homeName)));
                    List<Component> lore = new ArrayList<>();
                    for (String line : loreFormat) {
                        lore.add(color(line.replace("%delay%", String.valueOf(delay))));
                    }
                    meta.lore(lore);
                    bed.setItemMeta(meta);
                }
                gui.addItem(bed);
            }
            player.openInventory(gui);
            return true;

        } else if (command.getName().equalsIgnoreCase("delhome")) {
            Map<String, Location> homes = getPlayerHomes(player.getUniqueId());

            if (args.length == 0) {
                if (homes.isEmpty()) {
                    player.sendMessage(color(getPrefixedMessage("no-homes-del")));
                } else {
                    String available = String.join(", ", homes.keySet());
                    String msg = getPrefixedMessage("available-homes").replace("%homes%", available);
                    player.sendMessage(color(msg));
                }
                return true;
            }

            String homeName = cleanHomeName(args[0]);
            if (!homes.containsKey(homeName)) {
                String msg = getPrefixedMessage("home-not-found").replace("%home%", args[0]);
                player.sendMessage(color(msg));
                return true;
            }

            deleteHome(player.getUniqueId(), homeName);
            String msg = getPrefixedMessage("home-deleted").replace("%home%", homeName);
            player.sendMessage(color(msg));
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!pendingHomeCreation.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        if (message.equalsIgnoreCase("anuluj") || message.equalsIgnoreCase("cancel")) {
            pendingHomeCreation.remove(player.getUniqueId());
            player.sendMessage(color(getPrefixedMessage("creation-cancelled")));
            return;
        }

        String homeName = cleanHomeName(message);
        if (homeName.isEmpty()) {
            player.sendMessage(color(getPrefixedMessage("invalid-name")));
            return;
        }

        int maxHomes = getConfig().getInt("settings.max-homes", 3);
        Map<String, Location> homes = getPlayerHomes(player.getUniqueId());
        if (homes.size() >= maxHomes && !homes.containsKey(homeName)) {
            pendingHomeCreation.remove(player.getUniqueId());
            player.sendMessage(color(getPrefixedMessage("limit-reached").replace("%max%", String.valueOf(maxHomes))));
            return;
        }

        pendingHomeCreation.remove(player.getUniqueId());
        Location loc = player.getLocation().clone();

        Bukkit.getScheduler().runTask(this, () -> {
            saveHome(player.getUniqueId(), homeName, loc);

            String title = getConfig().getString("titles.home-set.title", "&aHome Ustawiony!").replace("%home%", homeName);
            String subtitle = getConfig().getString("titles.home-set.subtitle", "&7Nazwa: &e%home%").replace("%home%", homeName);
            showTitle(player, title, subtitle);

            String msg = getPrefixedMessage("home-set-success").replace("%home%", homeName);
            player.sendMessage(color(msg));
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getInventory().getType() == InventoryType.CHEST) {
            String rawTitle = LegacyComponentSerializer.legacySection().serialize(event.getView().title());
            String expectedTitle = getConfig().getString("gui.home-selector-title", "&8Wybierz swój Home");
            String cleanExpected = expectedTitle.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").trim();

            if (rawTitle.contains("Wybierz swój Home") || rawTitle.contains(cleanExpected)) {
                event.setCancelled(true);
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null || !clickedItem.hasItemMeta()) return;

                String displayName = LegacyComponentSerializer.legacySection().serialize(clickedItem.getItemMeta().displayName());
                String homeName = cleanHomeName(displayName);

                Map<String, Location> homes = getPlayerHomes(player.getUniqueId());
                if (!homes.containsKey(homeName)) {
                    player.sendMessage(color(getPrefixedMessage("home-not-found").replace("%home%", homeName)));
                    player.closeInventory();
                    return;
                }

                Location targetLoc = homes.get(homeName);
                player.closeInventory();
                startTeleportCountdown(player, homeName, targetLoc);
            }
        }
    }

    private void startTeleportCountdown(Player player, String homeName, Location targetLoc) {
        if (activeTeleports.containsKey(player.getUniqueId())) {
            activeTeleports.get(player.getUniqueId()).cancelTask();
            activeTeleports.remove(player.getUniqueId());
        }

        TeleportSession session = new TeleportSession(player, homeName, targetLoc, player.getLocation().clone());
        activeTeleports.put(player.getUniqueId(), session);

        int totalSeconds = getConfig().getInt("settings.teleport-delay", 10);

        BukkitTask task = new BukkitRunnable() {
            int secondsLeft = totalSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelSession(player.getUniqueId());
                    cancel();
                    return;
                }

                if (secondsLeft > 0) {
                    String title = getConfig().getString("titles.teleporting.title", "&eTeleportacja...");
                    String subtitle = getConfig().getString("titles.teleporting.subtitle", "&7Za &c%seconds%s &7nie ruszaj się!")
                            .replace("%seconds%", String.valueOf(secondsLeft));
                    showTitle(player, title, subtitle);
                    secondsLeft--;
                } else {
                    player.teleport(targetLoc);
                    String title = getConfig().getString("titles.teleport-success.title", "&aPrzeteleportowano!");
                    String subtitle = getConfig().getString("titles.teleport-success.subtitle", "&7Witaj w home: &e%home%")
                            .replace("%home%", homeName);
                    showTitle(player, title, subtitle);
                    cancelSession(player.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 20L);

        session.setTask(task);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("settings.cancel-on-move", true)) return;

        Player player = event.getPlayer();
        if (!activeTeleports.containsKey(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            String title = getConfig().getString("titles.teleport-cancelled-move.title", "&cTeleportacja przerwana!");
            String subtitle = getConfig().getString("titles.teleport-cancelled-move.subtitle", "&7Poruszyłeś się!");
            cancelTeleport(player, title, subtitle);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!activeTeleports.containsKey(victim.getUniqueId())) return;

        boolean isPlayerAttacker = false;
        if (event.getDamager() instanceof Player) {
            isPlayerAttacker = true;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player) {
            isPlayerAttacker = true;
        }

        boolean cancelOnPlayer = getConfig().getBoolean("settings.cancel-on-player-damage", true);
        boolean cancelOnMob = getConfig().getBoolean("settings.cancel-on-mob-damage", false);

        if ((isPlayerAttacker && cancelOnPlayer) || (!isPlayerAttacker && cancelOnMob)) {
            String title = getConfig().getString("titles.teleport-cancelled-damage.title", "&cTeleportacja przerwana!");
            String subtitle = getConfig().getString("titles.teleport-cancelled-damage.subtitle", "&7Zostałeś zaatakowany!");
            cancelTeleport(victim, title, subtitle);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelSession(event.getPlayer().getUniqueId());
        pendingHomeCreation.remove(event.getPlayer().getUniqueId());
    }

    private void cancelTeleport(Player player, String title, String subtitle) {
        cancelSession(player.getUniqueId());
        showTitle(player, title, subtitle);
    }

    private void cancelSession(UUID uuid) {
        TeleportSession session = activeTeleports.remove(uuid);
        if (session != null) {
            session.cancelTask();
        }
    }

    private void showTitle(Player player, String titleText, String subtitleText) {
        Title title = Title.title(
                color(titleText),
                color(subtitleText),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))
        );
        player.showTitle(title);
    }

    private void saveHome(UUID uuid, String name, Location loc) {
        String path = "players." + uuid.toString() + "." + name;
        homesConfig.set(path + ".world", loc.getWorld().getName());
        homesConfig.set(path + ".x", loc.getX());
        homesConfig.set(path + ".y", loc.getY());
        homesConfig.set(path + ".z", loc.getZ());
        homesConfig.set(path + ".yaw", loc.getYaw());
        homesConfig.set(path + ".pitch", loc.getPitch());
        saveHomesConfig();
    }

    private void deleteHome(UUID uuid, String name) {
        homesConfig.set("players." + uuid.toString() + "." + name, null);
        saveHomesConfig();
    }

    private Map<String, Location> getPlayerHomes(UUID uuid) {
        Map<String, Location> homes = new HashMap<>();
        ConfigurationSection section = homesConfig.getConfigurationSection("players." + uuid.toString());
        if (section == null) return homes;

        for (String key : section.getKeys(false)) {
            String worldName = section.getString(key + ".world");
            if (worldName == null || Bukkit.getWorld(worldName) == null) continue;

            double x = section.getDouble(key + ".x");
            double y = section.getDouble(key + ".y");
            double z = section.getDouble(key + ".z");
            float yaw = (float) section.getDouble(key + ".yaw");
            float pitch = (float) section.getDouble(key + ".pitch");

            homes.put(key, new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch));
        }
        return homes;
    }

    private static class TeleportSession {
        private final Player player;
        private final String homeName;
        private final Location targetLocation;
        private final Location startLocation;
        private BukkitTask task;

        public TeleportSession(Player player, String homeName, Location targetLocation, Location startLocation) {
            this.player = player;
            this.homeName = homeName;
            this.targetLocation = targetLocation;
            this.startLocation = startLocation;
        }

        public void setTask(BukkitTask task) {
            this.task = task;
        }

        public void cancelTask() {
            if (task != null) {
                task.cancel();
            }
        }
    }
}
