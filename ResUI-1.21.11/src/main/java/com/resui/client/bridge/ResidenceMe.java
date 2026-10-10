package com.resui.client.bridge;

import com.google.gson.JsonObject;

/**
 * ResidenceMe: 协议 v2 的 me 字段 —— 当前玩家的上下文。
 * 服务端未推送（或旧版桥接插件）时为 {@link #EMPTY}，界面按「未知」显示。
 */
public record ResidenceMe(String name,
                          String current,
                          String inSubzone,
                          boolean admin,
                          Limits limits,
                          String money,
                          int canCreate) {

    public static final ResidenceMe EMPTY =
            new ResidenceMe("", "", "", false, Limits.EMPTY, "", -1);

    /** Limits: /res limits 的解析结果（-1 表示无限 / 未知）。 */
    public record Limits(String group,
                         int maxResidences,
                         int maxSubzones,
                         int maxAreaX,
                         int maxAreaY,
                         int maxAreaZ,
                         int maxPhysical,
                         int maxSubzoneDepth,
                         int maxRents,
                         int maxRentDays,
                         int usedResidences,
                         boolean canSetMessage) {

        public static final Limits EMPTY =
                new Limits("", -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, true);

        public static Limits fromJson(JsonObject json) {
            return new Limits(
                    string(json, "group"),
                    integer(json, "maxResidences"),
                    integer(json, "maxSubzones"),
                    integer(json, "maxAreaX"),
                    integer(json, "maxAreaY"),
                    integer(json, "maxAreaZ"),
                    integer(json, "maxPhysical"),
                    integer(json, "maxSubzoneDepth"),
                    integer(json, "maxRents"),
                    integer(json, "maxRentDays"),
                    integer(json, "usedResidences"),
                    !json.has("canSetMessage") || json.get("canSetMessage").isJsonNull()
                            || json.get("canSetMessage").getAsBoolean());
        }

        /** limitText: 把上限值格式化成显示文本（-1 → 无限制）。 */
        public static String limitText(int value) {
            return value < 0 ? "无限制" : String.valueOf(value);
        }

        /** areaLimitText: 单区域尺寸上限，形如 128×256×128。 */
        public String areaLimitText() {
            if (maxAreaX < 0 || maxAreaY < 0 || maxAreaZ < 0) {
                return "无限制";
            }
            return maxAreaX + "×" + maxAreaY + "×" + maxAreaZ;
        }

        /** physicalLimitText: 单领地总体积上限。 */
        public String physicalLimitText() {
            return maxPhysical < 0 ? "无限制" : maxPhysical + " 方块";
        }
    }

    public static ResidenceMe fromJson(JsonObject json) {
        Limits parsed = json.has("limits") && json.get("limits").isJsonObject()
                ? Limits.fromJson(json.getAsJsonObject("limits"))
                : Limits.EMPTY;
        return new ResidenceMe(
                string(json, "name"),
                string(json, "current"),
                string(json, "inSubzone"),
                bool(json, "admin"),
                parsed,
                string(json, "money"),
                integer(json, "canCreate"));
    }

    /** currentText: 所在地显示文本（不在任何领地时给一句人话）。 */
    public String currentText() {
        if (current.isBlank()) {
            return "不在任何领地内";
        }
        return inSubzone.isBlank() ? current : current + " / " + inSubzone;
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static int integer(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : -1;
    }

    private static boolean bool(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
    }
}
