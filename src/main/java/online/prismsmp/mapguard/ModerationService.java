package online.prismsmp.mapguard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ModerationService {

    private final PrismMapGuard plugin;
    private final HttpClient httpClient;

    public ModerationService(PrismMapGuard plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Scan an image URL via Sightengine.
     * Returns null if approved, or a rejection reason string if blocked.
     * Must be called from an async thread.
     */
    public String scanImage(String imageUrl) {
        FileConfiguration config = plugin.getConfig();

        if (!config.getBoolean("sightengine.enabled", true)) {
            return null; // Moderation disabled, allow all
        }

        String apiUser = config.getString("sightengine.api-user", "");
        String apiSecret = config.getString("sightengine.api-secret", "");

        if (apiUser.isEmpty() || apiSecret.isEmpty()
                || apiUser.equals("YOUR_API_USER") || apiSecret.equals("YOUR_API_SECRET")) {
            plugin.getLogger().warning("Sightengine API credentials not configured!");
            return "NOT_CONFIGURED";
        }

        try {
            String encodedUrl = URLEncoder.encode(imageUrl, StandardCharsets.UTF_8);
            String requestUrl = "https://api.sightengine.com/1.0/check.json"
                    + "?url=" + encodedUrl
                    + "&models=nudity-2.1,offensive-2.0,gore-2.0,weapon,recreational_drug"
                    + "&api_user=" + apiUser
                    + "&api_secret=" + apiSecret;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Sightengine API returned status " + response.statusCode());
                return "API_ERROR";
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            plugin.getLogger().warning("Sightengine API call failed: " + e.getMessage());
            return "API_ERROR";
        }
    }

    /**
     * Parse the Sightengine JSON response and check thresholds.
     * Returns null if safe, or a reason string if blocked.
     */
    private String parseResponse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String status = json.has("status") ? json.get("status").getAsString() : "unknown";
            if (!status.equals("success")) {
                plugin.getLogger().warning("Sightengine returned status: " + status);
                return "API_ERROR";
            }

            FileConfiguration config = plugin.getConfig();
            List<String> violations = new ArrayList<>();

            // Check nudity
            if (json.has("nudity")) {
                JsonObject nudity = json.getAsJsonObject("nudity");
                checkScore(nudity, "sexual_activity", config.getDouble("thresholds.sexual-activity", 0.4), "Sexual content", violations);
                checkScore(nudity, "sexual_display", config.getDouble("thresholds.sexual-display", 0.4), "Sexual content", violations);
                checkScore(nudity, "erotica", config.getDouble("thresholds.erotica", 0.5), "Erotica", violations);
            }

            // Check offensive/hate symbols
            if (json.has("offensive")) {
                JsonObject offensive = json.getAsJsonObject("offensive");
                checkScore(offensive, "nazi", config.getDouble("thresholds.nazi", 0.3), "Nazi imagery", violations);
                checkScore(offensive, "supremacist", config.getDouble("thresholds.supremacist", 0.3), "Hate symbol", violations);
                checkScore(offensive, "confederate", config.getDouble("thresholds.confederate", 0.4), "Hate symbol", violations);
                checkScore(offensive, "isis", config.getDouble("thresholds.isis", 0.3), "Extremist imagery", violations);
                checkScore(offensive, "middle_finger", config.getDouble("thresholds.middle-finger", 0.5), "Offensive gesture", violations);
            }

            // Check gore
            if (json.has("gore")) {
                JsonObject gore = json.getAsJsonObject("gore");
                checkScore(gore, "prob", config.getDouble("thresholds.gore", 0.5), "Gore/violence", violations);
            }

            // Check weapon (can be direct number or object)
            if (json.has("weapon")) {
                double weaponScore = 0;
                try {
                    weaponScore = json.get("weapon").getAsDouble();
                } catch (Exception e) {
                    try {
                        weaponScore = json.getAsJsonObject("weapon").get("prob").getAsDouble();
                    } catch (Exception ignored) {}
                }
                if (weaponScore > config.getDouble("thresholds.weapon", 0.7)) {
                    violations.add("Weapons");
                }
            }

            // Check drugs
            if (json.has("recreational_drug")) {
                double drugScore = 0;
                try {
                    drugScore = json.get("recreational_drug").getAsDouble();
                } catch (Exception e) {
                    try {
                        drugScore = json.getAsJsonObject("recreational_drug").get("prob").getAsDouble();
                    } catch (Exception ignored) {}
                }
                if (drugScore > config.getDouble("thresholds.recreational-drug", 0.7)) {
                    violations.add("Drug content");
                }
            }

            if (violations.isEmpty()) {
                return null; // Safe
            }

            return String.join(", ", violations);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse Sightengine response: " + e.getMessage());
            return "API_ERROR";
        }
    }

    private void checkScore(JsonObject parent, String key, double threshold,
                            String label, List<String> violations) {
        if (parent.has(key)) {
            try {
                double score = parent.get(key).getAsDouble();
                if (score > threshold) {
                    violations.add(label);
                }
            } catch (Exception ignored) {}
        }
    }
}
