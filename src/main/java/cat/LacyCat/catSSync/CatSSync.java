package cat.LacyCat.catSSync;

import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class CatSSync extends JavaPlugin {
    private final String API_BASE = "http://lacycat.kro.kr/catservers/index.php";
    private String TOKEN = "a507896e999bb47255b6107b544ca5456d216d84a76432c5b39cbfb5b08c91b2";

    @Override
    public void onEnable() {
        getLogger().info("CatSync 플러그인이 활성화되었습니다.");
        // 서버 접속 시 인벤토리 동기화
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
    }

    // 아이템 메타데이터를 포함한 직렬화 메서드
    // 여러 아이템을 JSON 배열로 직렬화
    public JsonObject serializeInventory(List<ItemStack> items) {
        JsonObject inventoryData = new JsonObject();
        JsonArray inventoryArray = new JsonArray();

        for (ItemStack item : items) {
            inventoryArray.add(serializeItem(item));
        }

        inventoryData.add("inventory", inventoryArray);  // "inventory"라는 키로 JSON 배열을 추가
        return inventoryData;
    }

    // 여러 아이템을 JSON 배열에서 복원
    public List<ItemStack> deserializeInventory(JsonObject inventoryData) {
        List<ItemStack> items = new ArrayList<>();

        // "inventory"가 JSON 배열인지 확인
        if (inventoryData.has("inventory") && inventoryData.get("inventory").isJsonArray()) {
            JsonArray inventoryArray = inventoryData.getAsJsonArray("inventory");

            // 배열의 각 아이템을 deserializeItem을 통해 복원
            for (JsonElement element : inventoryArray) {
                items.add(deserializeItem(element.getAsJsonObject()));
            }
        } else {
            // JSON 배열이 아니라면 에러 처리
            System.out.println("Invalid inventory data: 'inventory' is not a JSON array.");
        }

        return items;
    }

    // 단일 아이템을 JSON 객체로 직렬화
    public JsonObject serializeItem(ItemStack item) {
        JsonObject itemData = new JsonObject();
        itemData.addProperty("type", item.getType().toString());
        itemData.addProperty("amount", item.getAmount());

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            JsonObject metaData = new JsonObject();

            if (meta.hasDisplayName()) {
                metaData.addProperty("name", meta.getDisplayName());
            }

            if (meta.hasLore()) {
                JsonArray loreArray = new JsonArray();
                for (String line : meta.getLore()) {
                    loreArray.add(line);
                }
                metaData.add("lore", loreArray);
            }

            if (meta.hasEnchants()) {
                JsonObject enchantments = new JsonObject();
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    enchantments.addProperty(entry.getKey().getKey().getKey(), entry.getValue());
                }
                metaData.add("enchants", enchantments);
            }

            if (meta.getPersistentDataContainer().has(new NamespacedKey("custom", "data"), PersistentDataType.STRING)) {
                metaData.addProperty("customData", meta.getPersistentDataContainer().get(new NamespacedKey("custom", "data"), PersistentDataType.STRING));
            }

            itemData.add("meta", metaData);
        }

        return itemData;
    }

    // 단일 아이템을 JSON 객체에서 복원
    public ItemStack deserializeItem(JsonObject itemData) {
        String type = itemData.get("type").getAsString();
        int amount = itemData.get("amount").getAsInt();
        ItemStack item = new ItemStack(org.bukkit.Material.valueOf(type), amount);

        if (itemData.has("meta")) {
            JsonElement metaDataElement = itemData.get("meta");

            // meta가 JsonObject일 경우
            if (metaDataElement.isJsonObject()) {
                JsonObject metaData = metaDataElement.getAsJsonObject();
                ItemMeta meta = item.getItemMeta();

                if (metaData.has("name")) {
                    meta.setDisplayName(metaData.get("name").getAsString());
                }

                if (metaData.has("lore")) {
                    List<String> loreList = new ArrayList<>();
                    for (JsonElement element : metaData.getAsJsonArray("lore")) {
                        loreList.add(element.getAsString());
                    }
                    meta.setLore(loreList);
                }

                if (metaData.has("enchants")) {
                    JsonObject enchantments = metaData.getAsJsonObject("enchants");
                    for (String key : enchantments.keySet()) {
                        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.fromString(key));
                        int level = enchantments.get(key).getAsInt();
                        meta.addEnchant(enchantment, level, true);
                    }
                }

                if (metaData.has("customData")) {
                    meta.getPersistentDataContainer().set(new NamespacedKey("custom", "data"), PersistentDataType.STRING, metaData.get("customData").getAsString());
                }

                item.setItemMeta(meta);
            }
            // meta가 빈 배열일 경우
            else if (metaDataElement.isJsonArray() && metaDataElement.getAsJsonArray().size() == 0) {
                // 빈 배열일 때 처리
                // 기본 ItemMeta를 설정하거나, 기존 ItemMeta가 없다면 아무것도 설정하지 않음
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    // 빈 meta 설정: 기본값을 설정하거나, 아무것도 설정하지 않음
                    meta.setDisplayName(""); // 기본 이름을 빈 문자열로 설정하거나
                    meta.setLore(new ArrayList<>()); // 빈 로어 리스트 설정
                    item.setItemMeta(meta);
                }
            }
        }

        return item;
    }

    // 인벤토리 동기화
    public void syncInventory(Player player) {
        UUID uuid = player.getUniqueId();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 서버로부터 인벤토리 데이터를 요청하는 URL 생성
                    String url = API_BASE + "?inventory=" + uuid;
                    String response = sendRequest(url, "GET", null, false);
                    Bukkit.getLogger().info(response);

                    // 응답이 null이 아니고 비어 있지 않은 경우 처리
                    if (response != null && !response.isEmpty()) {
                        // 서버 응답을 JsonElement로 파싱
                        JsonElement parsedElement = JsonParser.parseString(response);

                        // 전체 응답이 JsonObject인지 확인
                        if (!parsedElement.isJsonObject()) {
                            getLogger().warning("서버에서 잘못된 형식의 데이터를 반환했습니다: " + response);
                            return;
                        }

                        // "inventory" 배열을 바로 가져옴
                        JsonObject responseObject = parsedElement.getAsJsonObject();
                        JsonElement inventoryElement = responseObject.get("inventory"); // JsonArray여야 함

                        // "inventory"가 JsonArray인지 확인
                        if (inventoryElement != null && inventoryElement.isJsonArray()) {
                            JsonArray inventoryArray = inventoryElement.getAsJsonArray();

                            // JsonArray 크기만큼 ItemStack 배열 생성
                            ItemStack[] items = new ItemStack[inventoryArray.size()];
                            for (int i = 0; i < inventoryArray.size(); i++) {
                                // 각 아이템의 정보를 JsonObject로 가져옴
                                JsonObject itemData = inventoryArray.get(i).getAsJsonObject();
                                // JsonObject를 이용해 ItemStack 객체로 변환
                                items[i] = deserializeItem(itemData);
                            }

                            // 메인 스레드에서 플레이어 인벤토리 설정
                            Bukkit.getScheduler().runTask(CatSSync.this, () -> player.getInventory().setContents(items));
                            player.sendMessage(ChatColor.GREEN + "인벤토리가 동기화되었습니다!");
                        } else {
                            // inventory가 JsonArray가 아니거나 값이 없는 경우
                            getLogger().warning("서버에서 반환된 'inventory' 데이터는 배열 형식이 아니거나 값이 없습니다.");
                        }
                    } else {
                        // 서버 응답이 없거나 비어 있을 때
                        getLogger().warning("인벤토리 데이터를 불러오는 데 실패했습니다.");
                    }
                } catch (Exception e) {
                    // 예외 발생 시 오류 메시지 출력
                    getLogger().severe("인벤토리 불러오기 실패: " + e.getMessage());
                    e.printStackTrace();  // 예외 발생 시 스택 트레이스를 출력하여 오류를 상세히 확인
                }
            }
        }.runTaskAsynchronously(this);  // 비동기적으로 작업 실행
    }

    // 인벤토리 업로드
    public void uploadInventory(Player player) {
        UUID uuid = player.getUniqueId();
        JsonArray inventoryArray = new JsonArray();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) {
                JsonObject itemData = serializeItem(item);
                inventoryArray.add(itemData);
            }
        }

        JsonObject requestData = new JsonObject();
        requestData.add("inventory", inventoryArray);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String url = API_BASE + "?inventory=" + uuid;
                    sendRequest(url, "POST", requestData.toString(), true);
                } catch (Exception e) {
                    getLogger().severe("인벤토리 업로드 실패: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }


    // 밴 상태 확인
    public void checkBanStatus(Player player) {
        UUID uuid = player.getUniqueId();

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String url = API_BASE + "?ban=" + uuid;
                    String response = sendRequest(url, "GET", null, false);

                    if (response != null && !response.contains("error")) {
                        Bukkit.getScheduler().runTask(CatSSync.this, () -> {
                            player.kickPlayer(ChatColor.RED + "당신은 서버에서 밴되었습니다.");
                            getLogger().info(player.getName() + "이 밴 기록이 있어 접속이 차단되었습니다.");
                        });
                    }
                } catch (Exception e) {
                    getLogger().severe("밴 상태 확인 실패: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    // HTTP 요청 처리
    private String sendRequest(String urlString, String method, String body, boolean useToken) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");

        if (useToken) {
            conn.setRequestProperty("Authorization", "Bearer " + TOKEN);
        }

        conn.setDoOutput(true);

        if (body != null) {
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                return response.toString();
            }
        } else {
            getLogger().warning("HTTP 요청 실패. 응답 코드: " + responseCode);
            return null;
        }
    }
}
