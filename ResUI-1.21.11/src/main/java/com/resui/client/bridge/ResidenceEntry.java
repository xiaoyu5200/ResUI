package com.resui.client.bridge;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ResidenceEntry: 服务端推来的单个领地条目（只读展示用）。 */
public record ResidenceEntry(String name,
                             String owner,
                             String world,
                             int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ,
                             long size,
                             long baseSize,
                             String worth,
                             String sellWorth,
                             int areas,
                             boolean subzone,
                             boolean tp,
                             Map<String, Boolean> flags,
                             List<ResidenceMember> players) {

    public static ResidenceEntry fromJson(JsonObject json) {
        return new ResidenceEntry(
                string(json, "name"),
                string(json, "owner"),
                string(json, "world"),
                integer(json, "minX"), integer(json, "minY"), integer(json, "minZ"),
                integer(json, "maxX"), integer(json, "maxY"), integer(json, "maxZ"),
                longValue(json, "size"),
                longValue(json, "baseSize"),
                string(json, "worth"),
                string(json, "sellWorth"),
                integer(json, "areas"),
                bool(json, "subzone"),
                bool(json, "tp"),
                flags(json),
                players(json));
    }

    /** flag: 读取某个权限的当前有效值（服务端未推送时按关闭处理）。 */
    public boolean flag(String name) {
        return Boolean.TRUE.equals(flags.get(name));
    }

    private static Map<String, Boolean> flags(JsonObject json) {
        if (!json.has("flags") || !json.get("flags").isJsonObject()) {
            return Map.of();
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : json.getAsJsonObject("flags").entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsBoolean());
            }
        }
        return Map.copyOf(result);
    }

    private static List<ResidenceMember> players(JsonObject json) {
        if (!json.has("players") || !json.get("players").isJsonArray()) {
            return List.of();
        }
        List<ResidenceMember> result = new ArrayList<>();
        for (var element : json.getAsJsonArray("players")) {
            if (element.isJsonObject()) {
                ResidenceMember member = ResidenceMember.fromJson(element.getAsJsonObject());
                if (!member.name().isBlank()) {
                    result.add(member);
                }
            }
        }
        return List.copyOf(result);
    }

    /** member: 按玩家名查找成员（忽略大小写）。 */
    public ResidenceMember member(String playerName) {
        for (ResidenceMember member : players) {
            if (member.name().equalsIgnoreCase(playerName)) {
                return member;
            }
        }
        return null;
    }

    /** infoText: 副标题行：所有者 · 世界（坐标等详细信息放在“详细”弹窗里）。 */
    public String infoText() {
        StringBuilder builder = new StringBuilder();
        if (!owner.isBlank()) {
            builder.append(owner);
        }
        if (!world.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(world);
        }
        return builder.toString();
    }

    /** areaText: 坐标范围，供详细弹窗使用。 */
    public String areaText() {
        if (!hasArea()) {
            return "未知";
        }
        return minX + "," + minY + "," + minZ
                + " ~ "
                + maxX + "," + maxY + "," + maxZ;
    }

    /** sizeText: 领地大小：总体积 m³（水平面积 m²），与 /res info 的显示一致。 */
    public String sizeText() {
        if (size <= 0) {
            return "未知";
        }
        if (baseSize > 0) {
            return size + "m³（" + baseSize + "m²）";
        }
        return size + "m³";
    }

    /** worthText: 领地价值：购买价值（出售价格），与 /res info 的显示一致。 */
    public String worthText() {
        if (worth.isBlank()) {
            return "未启用经济";
        }
        if (sellWorth.isBlank()) {
            return worth;
        }
        return worth + "（" + sellWorth + "）";
    }

    public boolean hasArea() {
        return maxX >= minX && maxY >= minY && maxZ >= minZ && (minX != 0 || maxX != 0 || minZ != 0 || maxZ != 0);
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static int integer(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : 0;
    }

    private static long longValue(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : 0L;
    }

    private static boolean bool(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
    }
}