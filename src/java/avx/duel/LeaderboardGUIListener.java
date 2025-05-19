package avx.duel;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.ChatColor;

public class LeaderboardGUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.GOLD + "Duel Leaderboard")) {
            event.setCancelled(true); // prevent item movement
        }
    }
}