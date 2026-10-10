package com.resui.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ResidenceMember: 领地里被单独授权的玩家（“领地成员”）。
 * flags 只包含显式设置过的权限；未出现的项表示继承默认（返回 null）。
 */
public record ResidenceMember(String name, Map<String, Boolean> flags) {

    public static ResidenceMember fromJson(JsonObject json) {
        String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : "";
        Map<String, Boolean> flags = new LinkedHashMap<>();
        if (json.has("flags") && json.get("flags").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("flags").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    flags.put(entry.getKey(), entry.getValue().getAsBoolean());
                }
            }
        }
        return new ResidenceMember(name, Map.copyOf(flags));
    }

    /** flag: 返回该玩家此权限的设置值；null 表示未单独设置（继承默认）。 */
    public Boolean flag(String name) {
        return flags.get(name);
    }

    /** flagCount: 该玩家被单独设置的权限数量。 */
    public int flagCount() {
        return flags.size();
    }
}
