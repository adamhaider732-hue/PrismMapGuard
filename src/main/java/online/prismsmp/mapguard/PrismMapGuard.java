package online.prismsmp.mapguard;

import org.bukkit.plugin.java.JavaPlugin;

public class PrismMapGuard extends JavaPlugin {

    private ModerationService moderationService;
    private CommandInterceptor commandInterceptor;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        moderationService = new ModerationService(this);
        commandInterceptor = new CommandInterceptor(this, moderationService);

        getServer().getPluginManager().registerEvents(commandInterceptor, this);

        GuardCommand cmdHandler = new GuardCommand(this, commandInterceptor);
        getCommand("mapguard").setExecutor(cmdHandler);
        getCommand("mapguard").setTabCompleter(cmdHandler);

        // Check if ImageFrame is installed
        if (getServer().getPluginManager().getPlugin("ImageFrame") == null) {
            getLogger().warning("ImageFrame is not installed! PrismMapGuard requires ImageFrame to function.");
            getLogger().warning("Download: https://modrinth.com/plugin/imageframe");
        }

        // Check if API is configured
        String apiUser = getConfig().getString("sightengine.api-user", "");
        if (apiUser.equals("YOUR_API_USER") || apiUser.isEmpty()) {
            getLogger().warning("Sightengine API not configured! All map art uploads will be blocked.");
            getLogger().warning("Sign up free at https://sightengine.com and add credentials to config.yml");
        } else {
            getLogger().info("Sightengine moderation active.");
        }

        getLogger().info("PrismMapGuard v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("PrismMapGuard disabled.");
    }
}
