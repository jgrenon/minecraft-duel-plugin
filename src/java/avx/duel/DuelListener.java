package avx.duel;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.entity.Player;

public class DuelListener implements Listener {

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();
        DuelPlugin plugin = JavaPlugin.getPlugin(DuelPlugin.class);

        if (!plugin.ongoingDuels.containsKey(loser))
            return;

        Player winner = plugin.ongoingDuels.get(loser);

        // Update stats
        plugin.updateStats(winner, true);
        plugin.updateStats(loser, false);

        winner.sendMessage("§aYou won the duel against " + loser.getName() + "!");
        loser.sendMessage("§cYou lost the duel against " + winner.getName() + ".");

        Bukkit.broadcastMessage(ChatColor.GOLD + "[DUEL] " + ChatColor.YELLOW + winner.getName()
                + " defeated " + loser.getName() + " in a duel!");

        plugin.ongoingDuels.remove(loser);
        plugin.ongoingDuels.remove(winner);

        plugin.returnToOriginal(winner);
        plugin.returnToOriginal(loser);
    }
}