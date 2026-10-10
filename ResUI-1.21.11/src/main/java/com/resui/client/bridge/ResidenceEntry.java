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
                             List<ResidenceMember> players,
                             // ---- 协议 v2 ----
                             String parent,
                             String enterMessage,
                             String leaveMessage,
                             List<ItemRef> blacklist,
                             List<ItemRef> ignorelist,
                             List<CommandRef> commands,
                             List<SubzoneRef> subzones,
                             List<AreaRef> areaList,
                             String chatPrefix,
                             String chatColor,
                             int chatMembers,
                             // ---- 协议 v2：市场 / 出租 / 租约 / 银行 ----
                             boolean forSale,
                             int sellPrice,
                             String forSaleText,
                             boolean forRent,
                             boolean rented,
                             String rentableSubzone,
                             String rentRentedSubzone,
                             int rentDays,
                             int rentCost,
                             String rentCostText,
                             boolean rentAllowRenewing,
                             boolean rentStayInMarket,
                             boolean rentAllowAutoPay,
                             String renter,
                             String rentExpireText,
                             boolean rentAutoPay,
                             String leaseExpireText,
                             String leaseRenewCostText,
                             String bank,
                             double bankRaw) {

    /** ItemRef: 名单里的一个物品（id 是 Material 名，key 给客户端做本地化翻译）。 */
    public record ItemRef(String id, String key) {

        public static ItemRef fromJson(JsonObject json) {
            return new ItemRef(string(json, "id"), string(json, "key"));
        }

        /** display: 优先用客户端语言文件翻译，拿不到就显示英文 id。 */
        public String display() {
            if (key == null || key.isBlank()) {
                return id;
            }
            try {
                String translated = net.minecraft.network.chat.Component.translatable(key).getString();
                return translated == null || translated.isBlank() || translated.equals(key) ? id : translated;
            } catch (Throwable ignored) {
                return id;
            }
        }
    }

    /** CommandRef: 命令名单的一项（blocked=true 为禁用，false 为单独放行）。 */
    public record CommandRef(String cmd, boolean blocked) {

        public static CommandRef fromJson(JsonObject json) {
            return new CommandRef(string(json, "cmd"), bool(json, "blocked"));
        }

        public String display() {
            return (blocked ? "禁用 " : "放行 ") + "/" + cmd;
        }
    }

    /** SubzoneRef: 子领地简要信息（管理面板画树用）。 */
    public record SubzoneRef(String name, String owner, int areas, long size, boolean rented, boolean forRent) {

        public static SubzoneRef fromJson(JsonObject json) {
            return new SubzoneRef(
                    string(json, "name"),
                    string(json, "owner"),
                    integer(json, "areas"),
                    longValue(json, "size"),
                    bool(json, "rented"),
                    bool(json, "forRent"));
        }
    }

    /** AreaRef: 一块区域（cuboid）的坐标与体积。 */
    public record AreaRef(String id,
                          int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ,
                          long size,
                          boolean main) {

        public static AreaRef fromJson(JsonObject json) {
            return new AreaRef(
                    string(json, "id"),
                    integer(json, "minX"), integer(json, "minY"), integer(json, "minZ"),
                    integer(json, "maxX"), integer(json, "maxY"), integer(json, "maxZ"),
                    longValue(json, "size"),
                    bool(json, "main"));
        }

        public String rangeText() {
            return minX + "," + minY + "," + minZ + " ~ " + maxX + "," + maxY + "," + maxZ;
        }

        public String sizeText() {
            return size + "m³";
        }
    }

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
                players(json),
                string(json, "parent"),
                string(json, "messages", "enter"),
                string(json, "messages", "leave"),
                itemList(json, "blacklist"),
                itemList(json, "ignorelist"),
                commandList(json),
                subzoneList(json),
                areaList(json),
                string(json, "chat", "prefix"),
                string(json, "chat", "color"),
                integer(json, "chat", "memberCount"),
                bool(json, "forSale"),
                integer(json, "sellPrice"),
                string(json, "forSaleText"),
                bool(json, "forRent"),
                bool(json, "rented"),
                string(json, "rentableSubzone"),
                string(json, "rentRentedSubzone"),
                integer(json, "rentDays"),
                integer(json, "rentCost"),
                string(json, "rentCostText"),
                bool(json, "rentAllowRenewing"),
                bool(json, "rentStayInMarket"),
                bool(json, "rentAllowAutoPay"),
                string(json, "renter"),
                string(json, "rentExpireText"),
                bool(json, "rentAutoPay"),
                string(json, "leaseExpireText"),
                string(json, "leaseRenewCostText"),
                string(json, "bank"),
                decimal(json, "bankRaw"));
    }

    /** marketText: 市场状态的一行文案（出售 / 出租 / 都没有）。 */
    public String marketText() {
        if (forSale) {
            return "出售中：" + (forSaleText.isBlank() ? sellPrice + "" : forSaleText);
        }
        if (rented) {
            return "已出租给 " + (renter.isBlank() ? "某人" : renter)
                    + (rentExpireText.isBlank() ? "" : "（" + rentExpireText + "）");
        }
        if (forRent) {
            String price = rentCostText.isBlank() ? rentCost + "" : rentCostText;
            return "出租中：" + price + " / " + rentDays + " 天";
        }
        return "未挂牌";
    }

    private static double decimal(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : 0.0D;
    }

    private static List<ItemRef> itemList(JsonObject json, String key) {
        List<ItemRef> result = new ArrayList<>();
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        for (var element : json.getAsJsonArray(key)) {
            if (element.isJsonObject()) {
                ItemRef item = ItemRef.fromJson(element.getAsJsonObject());
                if (!item.id().isBlank()) {
                    result.add(item);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<CommandRef> commandList(JsonObject json) {
        List<CommandRef> result = new ArrayList<>();
        if (!json.has("blockedCommands") || !json.get("blockedCommands").isJsonArray()) {
            return List.of();
        }
        for (var element : json.getAsJsonArray("blockedCommands")) {
            if (element.isJsonObject()) {
                CommandRef command = CommandRef.fromJson(element.getAsJsonObject());
                if (!command.cmd().isBlank()) {
                    result.add(command);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<SubzoneRef> subzoneList(JsonObject json) {
        List<SubzoneRef> result = new ArrayList<>();
        if (!json.has("subzones") || !json.get("subzones").isJsonArray()) {
            return List.of();
        }
        for (var element : json.getAsJsonArray("subzones")) {
            if (element.isJsonObject()) {
                SubzoneRef subzone = SubzoneRef.fromJson(element.getAsJsonObject());
                if (!subzone.name().isBlank()) {
                    result.add(subzone);
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<AreaRef> areaList(JsonObject json) {
        List<AreaRef> result = new ArrayList<>();
        if (!json.has("areaList") || !json.get("areaList").isJsonArray()) {
            return List.of();
        }
        for (var element : json.getAsJsonArray("areaList")) {
            if (element.isJsonObject()) {
                result.add(AreaRef.fromJson(element.getAsJsonObject()));
            }
        }
        return List.copyOf(result);
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

    /** coordText: 紧凑坐标（列表行里用），形如 120,64,-340。 */
    public String coordText() {
        if (!hasArea()) {
            return "坐标未知";
        }
        return minX + "," + minY + "," + minZ;
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

    /** string: 读嵌套对象里的字符串（例如 messages.enter）；对象/字段缺失时返回空串。 */
    private static String string(JsonObject json, String object, String key) {
        if (!json.has(object) || !json.get(object).isJsonObject()) {
            return "";
        }
        return string(json.getAsJsonObject(object), key);
    }

    /** integer: 读嵌套对象里的整数；对象/字段缺失时按 0 处理。 */
    private static int integer(JsonObject json, String object, String key) {
        if (!json.has(object) || !json.get(object).isJsonObject()) {
            return 0;
        }
        return integer(json.getAsJsonObject(object), key);
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