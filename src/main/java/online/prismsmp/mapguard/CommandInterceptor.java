package online.prismsmp.mapguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CommandInterceptor implements Listener {

    private final PrismMapGuard plugin;
    private final ModerationService moderationService;

    // Players approved to run ImageFrame command (bypass re-scan)
    private final Set<UUID> approved = ConcurrentHashMap.newKeySet();
    // Players banned from uploading
    private final Set<UUID> banned = ConcurrentHashMap.newKeySet();
    // Track rejections for auto-ban: player UUID -> list of rejection timestamps
    private final Map<UUID, List<Long>> rejectionTracker = new ConcurrentHashMap<>();
    // Log file
    private final File logFile;
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public CommandInterceptor(PrismMapGuard plugin, ModerationService moderationService) {
        this.plugin = plugin;
        this.moderationService = moderationService;
        this.logFile = new File(plugin.getDataFolder(), "moderation.log");
        loadBans();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();

        // Only intercept /imageframe create commands
        if (!msg.startsWith("/imageframe create ") && !msg.startsWith("/ifm create ")) return;

        Player player = event.getPlayer();

        // Staff bypass
        if (player.hasPermission("mapguard.bypass")) return;

        // Already approved (re-execution after scan passed)
        if (approved.remove(player.getUniqueId())) return;

        // Banned check
        if (banned.contains(player.getUniqueId())) {
            event.setCancelled(true);
            String banMsg = plugin.getConfig().getString("messages.banned",
                    "&cYou have been banned from uploading images.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', banMsg));
            return;
        }

        // Cancel the command — we'll re-run it if the scan passes
        event.setCancelled(true);
        String originalCommand = event.getMessage();

        // Extract URL from the command
        // Format: /imageframe create <name> <url> <width> <height> [combined]
        String[] parts = originalCommand.split("\\s+");
        if (parts.length < 5) {
            player.sendMessage(ChatColor.RED + "Usage: /imageframe create <name> <url> <width> <height>");
            return;
        }

        String url = parts[3]; // Index: 0=/imageframe, 1=create, 2=name, 3=url

        // Validate URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            player.sendMessage(ChatColor.RED + "Invalid image URL. Must start with http:// or https://");
            return;
        }

        // Check if API is configured
        String apiUser = plugin.getConfig().getString("sightengine.api-user", "");
        if (apiUser.equals("YOUR_API_USER") || apiUser.isEmpty()) {
            String notConfigured = plugin.getConfig().getString("messages.not-configured",
                    "&cMap art moderation is not configured yet.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', notConfigured));
            return;
        }

        // Send scanning message
        String scanMsg = plugin.getConfig().getString("messages.scanning",
                "&7Scanning your image for prohibited content...");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', scanMsg));

        // Async scan
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result = moderationService.scanImage(url);

            // Back to main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                if (result == null) {
                    // APPROVED
                    logScan(player, url, "APPROVED", null);

                    String approveMsg = plugin.getConfig().getString("messages.approved",
                            "&aImage approved! Creating your map art...");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', approveMsg));

                    // Mark approved and re-run the original command
                    approved.add(player.getUniqueId());
                    player.performCommand(originalCommand.substring(1)); // remove leading /

                } else if (result.equals("API_ERROR")) {
                    // API error — block for safety
                    logScan(player, url, "ERROR", "API call failed");

                    String errorMsg = plugin.getConfig().getString("messages.api-error",
                            "&cImage scanning temporarily unavailable. Please try again later.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', errorMsg));

                } else if (result.equals("NOT_CONFIGURED")) {
                    String notConfigured = plugin.getConfig().getString("messages.not-configured",
                            "&cMap art moderation is not configured yet.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', notConfigured));

                } else {
                    // REJECTED
                    logScan(player, url, "REJECTED", result);

                    String rejectMsg = plugin.getConfig().getString("messages.rejected",
                            "&cImage rejected: contains prohibited content ({reason}).");
                    rejectMsg = rejectMsg.replace("{reason}", result);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', rejectMsg));

                    // Track for auto-ban
                    trackRejection(player);
                }
            });
        });
    }

    // ================================================================
    // Auto-ban tracking
    // ================================================================

    private void trackRejection(Player player) {
        if (!plugin.getConfig().getBoolean("auto-ban.enabled", true)) return;

        UUID uuid = player.getUniqueId();
        rejectionTracker.computeIfAbsent(uuid, k -> new ArrayList<>());
        List<Long> timestamps = rejectionTracker.get(uuid);

        long now = System.currentTimeMillis();
        timestamps.add(now);

        // Remove rejections older than 24 hours
        long cutoff = now - (24 * 60 * 60 * 1000);
        timestamps.removeIf(t -> t < cutoff);

        int threshold = plugin.getConfig().getInt("auto-ban.rejections-before-ban", 3);
        if (timestamps.size() >= threshold) {
            banPlayer(uuid);
            player.sendMessage(ChatColor.RED + "You have been automatically banned from uploading images due to repeated violations.");
            logScan(player, "N/A", "AUTO-BAN", "Reached " + threshold + " rejections in 24h");
            plugin.getLogger().warning("Auto-banned " + player.getName() + " from map art uploads.");
        }
    }

    // ================================================================
    // Ban management
    // ================================================================

    public void banPlayer(UUID uuid) {
        banned.add(uuid);
        saveBans();
    }

    public boolean unbanPlayer(UUID uuid) {
        boolean removed = banned.remove(uuid);
        if (removed) saveBans();
        return removed;
    }

    public boolean isBanned(UUID uuid) {
        return banned.contains(uuid);
    }

    private void saveBans() {
        File banFile = new File(plugin.getDataFolder(), "banned-uploaders.txt");
        try {
            banFile.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(banFile))) {
                for (UUID uuid : banned) {
                    pw.println(uuid.toString());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save bans: " + e.getMessage());
        }
    }

    private void loadBans() {
        File banFile = new File(plugin.getDataFolder(), "banned-uploaders.txt");
        if (!banFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(banFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    try { banned.add(UUID.fromString(line.trim())); }
                    catch (Exception ignored) {}
                }
            }
            plugin.getLogger().info("Loaded " + banned.size() + " banned uploaders.");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load bans: " + e.getMessage());
        }
    }

    // ================================================================
    // Logging
    // ================================================================

    public void logScan(Player player, String url, String result, String reason) {
        String entry = String.format("[%s] %s | %s (%s) | URL: %s | %s",
                LocalDateTime.now().format(fmt),
                result,
                player.getName(), player.getUniqueId(),
                url,
                reason != null ? reason : "Clean");

        plugin.getLogger().info(entry);

        try {
            logFile.getParentFile().mkdirs();
            try (FileWriter fw = new FileWriter(logFile, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(entry);
                bw.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write moderation log: " + e.getMessage());
        }
    }

    public List<String> getLog(String playerFilter, int maxLines) {
        List<String> results = new ArrayList<>();
        if (!logFile.exists()) return results;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            List<String> all = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (playerFilter == null || line.toLowerCase().contains(playerFilter.toLowerCase())) {
                    all.add(line);
                }
            }
            int start = Math.max(0, all.size() - maxLines);
            results.addAll(all.subList(start, all.size()));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read log: " + e.getMessage());
        }
        return results;
    }

    public int getTotalScans() {
        if (!logFile.exists()) return 0;
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            while (br.readLine() != null) count++;
        } catch (IOException e) { /* ignore */ }
        return count;
    }

    public int getBanCount() { return banned.size(); }
}
