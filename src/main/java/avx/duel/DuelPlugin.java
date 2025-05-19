package avx.duel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.bukkit.ChatColor;

import java.util.*;

public class DuelPlugin extends JavaPlugin {

    private final Map<Player, Player> pendingChallenges = new HashMap<>();
    private final Map<Player, Long> challengeTimestamps = new HashMap<>();
    public final Map<Player, Player> ongoingDuels = new HashMap<>();
    public final Map<UUID, Stats> playerStats = new HashMap<>();
    private final Map<String, DuelArena> arenas = new HashMap<>();
    private final Map<UUID, Location> originalLocations = new HashMap<>();

    private int duelTimeoutSeconds;
    private int countdownSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadArenas();
        getServer().getPluginManager().registerEvents(new DuelListener(), this);
        getServer().getPluginManager().registerEvents(new LeaderboardGUIListener(), this);
        getServer().getPluginManager().registerEvents(new DuelCommandFilter(), this);

        duelTimeoutSeconds = getConfig().getInt("duel_timeout_seconds", 30);
        countdownSeconds = getConfig().getInt("countdown_seconds", 5);
        this.getCommand("duel").setExecutor(this);

        this.startDuelTimeoutTask();
    }

    public void loadArenas() {
        arenas.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("arenas");
        if (section == null)
            return;

        for (String name : section.getKeys(false)) {
            Location slot1 = loadLocation(section.getConfigurationSection(name + ".slot1"));
            Location slot2 = loadLocation(section.getConfigurationSection(name + ".slot2"));
            if (slot1 != null && slot2 != null) {
                arenas.put(name, new DuelArena(slot1, slot2));
            }
        }
    }

    private Location loadLocation(ConfigurationSection cs) {
        if (cs == null)
            return null;
        World world = Bukkit.getWorld(cs.getString("world"));
        double x = cs.getDouble("x");
        double y = cs.getDouble("y");
        double z = cs.getDouble("z");
        return (world == null) ? null : new Location(world, x, y, z);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 1) {
            String arg = args[0];

            if (arg.equalsIgnoreCase("accept")) {
                if (!pendingChallenges.containsKey(player)) {
                    player.sendMessage("You have no pending duel requests.");
                    return true;
                }

                Player challenger = pendingChallenges.remove(player);
                challengeTimestamps.remove(player);

                if (challenger == null || !challenger.isOnline()) {
                    player.sendMessage("The challenger is no longer online.");
                    return true;
                }

                player.sendMessage("Duel starting in " + countdownSeconds + " seconds...");
                challenger.sendMessage(
                        player.getName() + " has accepted your duel! Starting in " + countdownSeconds + " seconds...");

                DuelArena arena = arenas.getOrDefault("default", null);
                if (arena == null) {
                    player.sendMessage("No arena is configured.");
                    return true;
                }

                new BukkitRunnable() {
                    int seconds = countdownSeconds;

                    @Override
                    public void run() {
                        if (seconds <= 0) {
                            originalLocations.put(challenger.getUniqueId(), challenger.getLocation());
                            originalLocations.put(player.getUniqueId(), player.getLocation());
                            challenger.teleport(arena.slot1);
                            player.teleport(arena.slot2);
                            challenger.sendMessage(ChatColor.RED + "Fight!");
                            player.sendMessage(ChatColor.RED + "Fight!");
                            cancel();
                            return;
                        }
                        challenger.sendMessage(ChatColor.YELLOW + "Teleporting in " + seconds + "...");
                        player.sendMessage(ChatColor.YELLOW + "Teleporting in " + seconds + "...");
                        seconds--;
                    }
                }.runTaskTimer(this, 0L, 20L); // 20 ticks = 1 second

                return true;
            }

            if (arg.equalsIgnoreCase("deny")) {
                if (!pendingChallenges.containsKey(player)) {
                    player.sendMessage("You have no duel to deny.");
                    return true;
                }

                Player challenger = pendingChallenges.remove(player);
                challengeTimestamps.remove(player);

                player.sendMessage("You have denied the duel request.");
                if (challenger != null && challenger.isOnline()) {
                    challenger.sendMessage(player.getName() + " has denied your duel request.");
                }

                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("leaderboard")) {
                player.sendMessage("§6--- Duel Leaderboard ---");
                playerStats.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue().wins, a.getValue().wins))
                        .limit(5)
                        .forEach(entry -> {
                            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                            Stats s = entry.getValue();
                            player.sendMessage("§e" + name + ": " + s.wins + "W / " + s.losses + "L");
                        });
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("leaderboard")) {
                openLeaderboardGUI(player);
                return true;
            }

            if (args.length == 3 && args[0].equalsIgnoreCase("setarena")) {
                String name = args[1];
                String slot = args[2].toLowerCase();

                if (!slot.equals("slot1") && !slot.equals("slot2")) {
                    player.sendMessage("Slot must be 'slot1' or 'slot2'.");
                    return true;
                }

                Location loc = player.getLocation();
                String base = "arenas." + name + "." + slot;
                getConfig().set(base + ".world", loc.getWorld().getName());
                getConfig().set(base + ".x", loc.getX());
                getConfig().set(base + ".y", loc.getY());
                getConfig().set(base + ".z", loc.getZ());

                saveConfig();
                loadArenas();

                player.sendMessage("Arena " + name + " " + slot + " set to your current location.");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("forfeit")) {
                if (!ongoingDuels.containsKey(player) && !ongoingDuels.containsValue(player)) {
                    player.sendMessage(ChatColor.RED + "You are not in a duel.");
                    return true;
                }

                Player opponent = ongoingDuels.get(player);
                if (opponent == null) {
                    // Try reverse map lookup
                    for (Map.Entry<Player, Player> entry : ongoingDuels.entrySet()) {
                        if (entry.getValue().equals(player)) {
                            opponent = entry.getKey();
                            break;
                        }
                    }
                }

                if (opponent == null || !opponent.isOnline()) {
                    player.sendMessage(ChatColor.RED + "Could not resolve opponent.");
                    ongoingDuels.remove(player);
                    return true;
                }

                // Update stats
                updateStats(opponent, true);
                updateStats(player, false);

                Bukkit.broadcastMessage(ChatColor.GOLD + "[DUEL] " + ChatColor.YELLOW + opponent.getName()
                        + " won by forfeit against " + player.getName() + "!");

                player.sendMessage(ChatColor.RED + "You forfeited the duel.");
                opponent.sendMessage(ChatColor.GREEN + player.getName() + " forfeited. You win!");

                // Clean up duel state
                ongoingDuels.remove(player);
                ongoingDuels.remove(opponent);

                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                opponent.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());

                returnToOriginal(player);
                returnToOriginal(opponent);

                return true;
            }

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                player.sendMessage(ChatColor.GOLD + "---------- /duel Help ----------");
                player.sendMessage(ChatColor.YELLOW + "/duel <player> " + ChatColor.WHITE
                        + "- Challenge another player to a duel.");
                player.sendMessage(
                        ChatColor.YELLOW + "/duel accept " + ChatColor.WHITE + "- Accept a pending duel challenge.");
                player.sendMessage(
                        ChatColor.YELLOW + "/duel deny " + ChatColor.WHITE + "- Deny a pending duel challenge.");
                player.sendMessage(ChatColor.YELLOW + "/duel forfeit " + ChatColor.WHITE
                        + "- Forfeit your current duel (only usable during a duel).");
                player.sendMessage(ChatColor.YELLOW + "/duel leaderboard " + ChatColor.WHITE
                        + "- View the top duelists in a GUI.");
                player.sendMessage(ChatColor.YELLOW + "/duel setarena <name> <slot1|slot2> " + ChatColor.WHITE
                        + "- Set arena teleport points at your current location.");
                player.sendMessage(ChatColor.GOLD + "-------------------------------");
                return true;
            }

            // Duel <player>
            Player target = Bukkit.getPlayer(arg);
            if (target == null || !target.isOnline()) {
                player.sendMessage("That player is not online.");
                return true;
            }

            if (target.equals(player)) {
                player.sendMessage("You cannot duel yourself.");
                return true;
            }

            pendingChallenges.put(target, player);
            challengeTimestamps.put(target, System.currentTimeMillis());

            player.sendMessage("You have challenged " + target.getName() + " to a duel.");
            target.sendMessage(player.getName() + " has challenged you to a duel! Type /duel accept or /duel deny.");
            return true;
        }

        player.sendMessage("Usage: /duel <player> | accept | deny");
        return true;
    }

    @Override
    public void onDisable() {
        pendingChallenges.clear();
        challengeTimestamps.clear();
    }

    public void updateStats(Player player, boolean won) {
        UUID id = player.getUniqueId();
        Stats stats = playerStats.getOrDefault(id, new Stats());
        if (won)
            stats.wins++;
        else
            stats.losses++;
        playerStats.put(id, stats);
    }

    public void setupScoreboard(Player p1, Player p2) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();
        Objective obj = board.registerNewObjective("duel", "dummy", "§6Duel vs " + p2.getName());
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.getScore("§aYou: " + p1.getName()).setScore(1);
        obj.getScore("§cEnemy: " + p2.getName()).setScore(0);
        p1.setScoreboard(board);
    }

    public void openLeaderboardGUI(Player viewer) {
        int size = 9; // Can be increased if more players are shown
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.GOLD + "Duel Leaderboard");

        List<Map.Entry<UUID, Stats>> topStats = playerStats.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().wins, a.getValue().wins))
                .limit(size)
                .toList();

        int i = 0;
        for (Map.Entry<UUID, Stats> entry : topStats) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
            Stats s = entry.getValue();

            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(p);
                meta.setDisplayName(ChatColor.YELLOW + p.getName());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GREEN + "Wins: " + s.wins);
                lore.add(ChatColor.RED + "Losses: " + s.losses);
                meta.setLore(lore);
                skull.setItemMeta(meta);
            }

            gui.setItem(i++, skull);
        }

        viewer.openInventory(gui);
    }

    public void returnToOriginal(Player player) {
        Location loc = originalLocations.remove(player.getUniqueId());
        if (loc != null) {
            player.teleport(loc);
        }
    }

    private void startDuelTimeoutTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                List<Player> toRemove = new ArrayList<>();

                for (Map.Entry<Player, Long> entry : challengeTimestamps.entrySet()) {
                    if ((now - entry.getValue()) >= duelTimeoutSeconds * 1000L) {
                        Player challenged = entry.getKey();
                        Player challenger = pendingChallenges.get(challenged);

                        if (challenged != null && challenged.isOnline()) {
                            challenged.sendMessage("Your duel request has expired.");
                        }

                        if (challenger != null && challenger.isOnline()) {
                            challenger.sendMessage("Duel request to " + challenged.getName() + " has expired.");
                        }

                        toRemove.add(challenged);
                    }
                }

                for (Player p : toRemove) {
                    challengeTimestamps.remove(p);
                    pendingChallenges.remove(p);
                }

            }
        }.runTaskTimer(this, 0L, 20L * 60); // Runs every 60 seconds

    }
}