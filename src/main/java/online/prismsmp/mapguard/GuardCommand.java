package online.prismsmp.mapguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class GuardCommand implements CommandExecutor, TabCompleter {

    private final PrismMapGuard plugin;
    private final CommandInterceptor interceptor;

    public GuardCommand(PrismMapGuard plugin, CommandInterceptor interceptor) {
        this.plugin = plugin;
        this.interceptor = interceptor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("mapguard.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "log" -> handleLog(sender, args);
            case "ban" -> handleBan(sender, args);
            case "unban" -> handleUnban(sender, args);
            case "stats" -> handleStats(sender);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleLog(CommandSender sender, String[] args) {
        String filter = null;
        int count = 10;

        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("recent")) {
                count = args.length >= 3 ? parseIntSafe(args[2], 10) : 10;
            } else {
                filter = args[1];
                count = args.length >= 3 ? parseIntSafe(args[2], 10) : 10;
            }
        }

        List<String> entries = interceptor.getLog(filter, count);
        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No log entries found.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "--- MapGuard Log (" + entries.size() + " entries) ---");
        for (String entry : entries) {
            String colored = entry
                    .replace("APPROVED", ChatColor.GREEN + "APPROVED" + ChatColor.GRAY)
                    .replace("REJECTED", ChatColor.RED + "REJECTED" + ChatColor.GRAY)
                    .replace("AUTO-BAN", ChatColor.DARK_RED + "AUTO-BAN" + ChatColor.GRAY)
                    .replace("ERROR", ChatColor.YELLOW + "ERROR" + ChatColor.GRAY);
            sender.sendMessage(ChatColor.GRAY + colored);
        }
    }

    private void handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /mapguard ban <player>");
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
            return;
        }

        if (interceptor.isBanned(target.getUniqueId())) {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " is already banned from uploading.");
            return;
        }

        interceptor.banPlayer(target.getUniqueId());
        sender.sendMessage(ChatColor.GREEN + target.getName() + " has been banned from uploading map art.");
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /mapguard unban <player>");
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);

        if (interceptor.unbanPlayer(target.getUniqueId())) {
            sender.sendMessage(ChatColor.GREEN + target.getName() + " has been unbanned from uploading.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " was not banned.");
        }
    }

    private void handleStats(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- MapGuard Stats ---");
        sender.sendMessage(ChatColor.GRAY + "Total scans: " + ChatColor.WHITE + interceptor.getTotalScans());
        sender.sendMessage(ChatColor.GRAY + "Banned uploaders: " + ChatColor.WHITE + interceptor.getBanCount());

        String apiUser = plugin.getConfig().getString("sightengine.api-user", "");
        boolean configured = !apiUser.isEmpty() && !apiUser.equals("YOUR_API_USER");
        sender.sendMessage(ChatColor.GRAY + "Sightengine: " +
                (configured ? ChatColor.GREEN + "Configured" : ChatColor.RED + "NOT CONFIGURED"));
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "PrismMapGuard config reloaded.");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- MapGuard Commands ---");
        sender.sendMessage(ChatColor.YELLOW + "/mg log [player]" + ChatColor.GRAY + " — View scan log");
        sender.sendMessage(ChatColor.YELLOW + "/mg log recent [count]" + ChatColor.GRAY + " — Recent entries");
        sender.sendMessage(ChatColor.YELLOW + "/mg ban <player>" + ChatColor.GRAY + " — Ban from uploading");
        sender.sendMessage(ChatColor.YELLOW + "/mg unban <player>" + ChatColor.GRAY + " — Unban player");
        sender.sendMessage(ChatColor.YELLOW + "/mg stats" + ChatColor.GRAY + " — View stats");
        sender.sendMessage(ChatColor.YELLOW + "/mg reload" + ChatColor.GRAY + " — Reload config");
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("mapguard.admin")) return List.of();

        if (args.length == 1) {
            return filter(args[0], "log", "ban", "unban", "stats", "reload");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("ban") || args[0].equalsIgnoreCase("unban"))) {
            return null; // Player names
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("log")) {
            List<String> opts = new ArrayList<>();
            opts.add("recent");
            Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName()));
            return filter(args[1], opts.toArray(new String[0]));
        }
        return List.of();
    }

    private List<String> filter(String input, String... options) {
        List<String> results = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(input.toLowerCase())) results.add(opt);
        }
        return results;
    }
}
