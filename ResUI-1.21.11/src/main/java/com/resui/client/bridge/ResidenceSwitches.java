package com.resui.client.bridge;

import com.google.gson.JsonObject;

/**
 * ResidenceSwitches: 协议 v2 的 switches 字段 —— 服务端 Residence 的功能开关。
 * 全部取自服务端 plugins/Residence/config.yml，界面据此把未启用的功能置灰，
 * 而不是给玩家一个「点了就报错」的按钮。旧版桥接插件不推送时为 {@link #DEFAULT}（全部按可用处理）。
 */
public record ResidenceSwitches(boolean economy,
                                boolean rentSystem,
                                boolean leaseSystem,
                                boolean bank,
                                boolean residenceChat,
                                boolean raid,
                                double bankCapacity) {

    /** 旧服务端：全部按可用处理（宁可让按钮可点，也不要误判成不可用）。 */
    public static final ResidenceSwitches DEFAULT =
            new ResidenceSwitches(true, true, false, true, true, false, 0.0D);

    public static ResidenceSwitches fromJson(JsonObject json) {
        return new ResidenceSwitches(
                bool(json, "economy", true),
                bool(json, "rentSystem", true),
                bool(json, "leaseSystem", false),
                bool(json, "bank", true),
                bool(json, "residenceChat", true),
                bool(json, "raid", false),
                decimal(json, "bankCapacity"));
    }

    /** bankCapacityText: 银行容量（0 = 无上限）。 */
    public String bankCapacityText() {
        return bankCapacity <= 0.0D ? "无上限" : String.valueOf(bankCapacity);
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static double decimal(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : 0.0D;
    }
}
