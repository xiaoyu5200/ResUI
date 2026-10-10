package com.resui.bridge;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.economy.EconomyInterface;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ResUI-Bridge: 读取 Residence 数据，通过插件消息通道 resui:main 推送给装了 ResUI 的客户端。
 * 通道数据格式：VarInt 长度 + UTF-8 JSON（与客户端 FriendlyByteBuf.readUtf 对齐）。
 */
public final class ResUiBridgePlugin extends JavaPlugin implements Listener {
    public static final String CHANNEL = "resui:main";
    public static final String REFRESH_CHANNEL = "resui:refresh";
    public static final String ACTION_CHANNEL = "resui:action";
    private static final int MAX_PUBLIC = 300;
    /** trusted 权限组的默认内容（反射读取失败时的兜底，取自 Residence 默认配置）。 */
    private static final Set<String> DEFAULT_TRUSTED_FLAGS = new LinkedHashSet<>(List.of(
            "container", "harvest", "move", "shear", "use", "destroy", "riding", "animalkilling",
            "beacon", "hook", "trade", "mobkilling", "build", "chat", "vehicledestroy", "leash", "tp"));
    /** 合法的 Minecraft 玩家名。 */
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** 语言文件里权限说明的行格式：Translated: honeycomb - 获得蜜脾 */
    private static final Pattern TRANSLATED_LINE = Pattern.compile("^\\s*Translated:\\s*(.+)$");
    /**
     * 官方语言文件尚未提供中文说明的权限（Residence 新增的 flag），
     * 这里按官方英文 Description 补一份，避免列表里出现英文。
     */
    private static final Map<String, String> FALLBACK_LABELS = Map.ofEntries(
            Map.entry("animalfeeding", "允许喂食动物"),
            Map.entry("animalgriefing", "允许动物破坏方块"),
            Map.entry("boarding", "允许生物乘坐载具"),
            Map.entry("copperoxidation", "允许铜块氧化"),
            Map.entry("coraldryup", "允许珊瑚干枯"),
            Map.entry("goathorn", "使用山羊角"),
            Map.entry("golemopenchest", "允许傀儡打开箱子"),
            Map.entry("minecartsuction", "允许漏斗矿车吸取物品"),
            Map.entry("mobgriefing", "允许怪物破坏方块"),
            Map.entry("push", "允许玩家推动实体"),
            Map.entry("raid", "允许触发袭击"),
            Map.entry("skulk", "阻止幽匿催发体扩散"),
            Map.entry("visualizer", "进入领地显示粒子边界"),
            Map.entry("vehicleplacing", "允许放置载具"),
            Map.entry("windexplode", "允许风弹爆炸"));
    /** Residence 语言文件里的「flag → 中文说明」缓存。 */
    private Map<String, String> flagLabels;

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        // 客户端的刷新请求（自定义通道，不走命令树）；回调可能在任何线程，切到玩家区域线程再取数据
        getServer().getMessenger().registerIncomingPluginChannel(this, REFRESH_CHANNEL,
                (channel, player, message) -> player.getScheduler().run(this, task -> sendSnapshot(player), null));
        // 客户端的写操作请求：执行 + 校验结果 + 回推最新数据
        getServer().getMessenger().registerIncomingPluginChannel(this, ACTION_CHANNEL,
                (channel, player, message) -> player.getScheduler().run(this, task -> handleAction(player, message), null));
        if (getCommand("resbridge") != null) {
            getCommand("resbridge").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) {
                    // Folia/Paper 通用：在玩家所属的区域线程上执行
                    player.getScheduler().run(this, task -> {
                        sendSnapshot(player);
                        player.sendMessage("§a[ResUI] 已刷新领地数据。");
                    }, null);
                } else {
                    sender.sendMessage("§c只有玩家可以执行该命令。");
                }
                return true;
            });
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 延迟 2 秒等客户端完成通道注册；只给装了 ResUI 的客户端推送，避免打扰原版玩家。
        // 使用实体调度器（Folia 兼容），任务在玩家所属区域线程执行。
        player.getScheduler().runDelayed(this, task -> {
            if (player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
                sendSnapshot(player);
            }
        }, null, 40L);
    }

    /** sendSnapshot: 组装并在插件消息通道上发送当前玩家的领地快照。 */
    private void sendSnapshot(Player player) {
        sendSnapshot(player, null);
    }

    /** sendSnapshot: 附带操作结果（可为 null）一起推送给客户端，客户端据此显示提示条。 */
    private void sendSnapshot(Player player, JsonObject result) {
        try {
            JsonObject root = buildJson(player);
            if (result != null) {
                root.add("result", result);
            }
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 5);
            writeVarInt(out, bytes.length);
            out.write(bytes);
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
        } catch (Throwable throwable) {
            getLogger().warning("推送领地数据失败: " + throwable);
        }
    }

    private JsonObject buildJson(Player player) {
        JsonObject root = new JsonObject();
        JsonArray own = new JsonArray();
        JsonArray publicList = new JsonArray();

        if (getServer().getPluginManager().getPlugin("Residence") == null) {
            root.add("own", own);
            root.add("public", publicList);
            root.addProperty("time", System.currentTimeMillis());
            root.addProperty("error", "Residence 未安装");
            return root;
        }

        ResidenceManager manager = Residence.getInstance().getResidenceManager();
        UUID uuid = player.getUniqueId();
        Map<String, ClaimedResidence> all = manager.getResidences();
        List<ClaimedResidence> residences = new ArrayList<>(all.values());
        residences.sort(Comparator.comparing(ClaimedResidence::getName, String.CASE_INSENSITIVE_ORDER));

        // 经济接口：用于生成与 /res info 一致的「领地价值」文案（含货币名）
        EconomyInterface economy = Residence.getInstance().getEconomyManager();

        int publicCount = 0;
        for (ClaimedResidence residence : residences) {
            boolean tp = residence.getPermissions().has(Flags.tp, false);
            boolean isOwn = residence.isOwner(uuid)
                    || (residence.isSubzone() && residence.getParent() != null && residence.getParent().isOwner(uuid));
            if (isOwn) {
                // tp flag 只限制「别人」能否传送；领地主人本人永远可以传送，所以 own 固定为 true
                own.add(toJson(residence, true, economy, true));
            } else if (!residence.isSubzone() && tp && publicCount < MAX_PUBLIC) {
                // 公共领地不带权限数据（管理面板只对自己的领地开放），避免包体过大
                publicList.add(toJson(residence, true, economy, false));
                publicCount++;
            }
        }

        // 权限列表：与官方 /res set GUI 完全一致（含按玩家权限节点的过滤），
        // 中文说明取自 Residence 官方语言文件。
        JsonArray allFlags = new JsonArray();
        Map<String, String> labels = flagLabels();
        for (String name : possibleFlags()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("label", labels.getOrDefault(name, name));
            allFlags.add(item);
        }
        root.add("allFlags", allFlags);

        root.add("own", own);
        root.add("public", publicList);
        root.addProperty("time", System.currentTimeMillis());
        return root;
    }

    // ---------------------------------------------------------------- 写操作

    /** handleAction: 处理客户端发来的写操作请求，并把执行结果随最新快照一起回推。 */
    private void handleAction(Player player, byte[] message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        try {
            JsonObject request = JsonParser.parseString(readPayload(message)).getAsJsonObject();
            String action = string(request, "action");
            String residenceName = string(request, "residence");
            ClaimedResidence claim = ClaimedResidence.getByName(residenceName);
            if (claim == null) {
                result.addProperty("message", "领地不存在：" + residenceName);
            } else if (!claim.isOwner(player) && !player.hasPermission("residence.admin")) {
                result.addProperty("message", "你没有该领地的管理权限");
            } else {
                runAction(player, claim, action, request, result);
            }
        } catch (Throwable throwable) {
            result.addProperty("message", "执行失败：" + throwable);
        }
        sendSnapshot(player, result);
    }

    /**
     * runAction: 直接调用 Residence 的权限 API（不使用命令），因此不会往玩家聊天栏刷命令回显。
     * 所有权与参数合法性由本插件校验，操作后立即读取领地状态做验证并回传结果。
     */
    private void runAction(Player player, ClaimedResidence claim, String action,
                           JsonObject request, JsonObject result) {
        FlagPermissions permissions = claim.getPermissions();
        switch (action == null ? "" : action) {
            case "set_flag": {
                String flag = string(request, "flag");
                String value = string(request, "value");
                if (!isSettableFlag(flag) || !isBooleanText(value)) {
                    result.addProperty("message", "权限参数不合法");
                    return;
                }
                permissions.setFlag(flag, stateOf(value));
                report(result, permissions.has(flag, false) == Boolean.parseBoolean(value),
                        "已设置 " + flag + " = " + value,
                        "设置未生效，请点右上角刷新查看");
                return;
            }
            case "set_member_flag": {
                String target = string(request, "player");
                String flag = string(request, "flag");
                String value = string(request, "value");
                if (!PLAYER_NAME.matcher(target).matches() || !isSettableFlag(flag)
                        || (!isBooleanText(value) && !"remove".equals(value))) {
                    result.addProperty("message", "成员权限参数不合法");
                    return;
                }
                permissions.setPlayerFlag(target, flag, stateOf(value));
                Map<String, Boolean> flags = findPlayerFlags(claim, target);
                boolean ok = "remove".equals(value)
                        ? (flags == null || !flags.containsKey(flag))
                        : (flags != null && Boolean.valueOf(value).equals(flags.get(flag)));
                report(result, ok, "已更新 " + target + " 的 " + flag, "未生效，请点右上角刷新查看");
                return;
            }
            case "add_member": {
                String target = string(request, "player");
                if (!PLAYER_NAME.matcher(target).matches()) {
                    result.addProperty("message", "玩家名不合法");
                    return;
                }
                // 与 /res padd 一致：给成员套用 trusted 权限组
                for (String flag : trustedFlags()) {
                    permissions.setPlayerFlag(target, flag, FlagPermissions.FlagState.TRUE);
                }
                report(result, findPlayerFlags(claim, target) != null,
                        "已添加成员 " + target,
                        "添加失败：该玩家可能刚刚离线");
                return;
            }
            case "remove_member": {
                String target = string(request, "player");
                if (!PLAYER_NAME.matcher(target).matches()) {
                    result.addProperty("message", "玩家名不合法");
                    return;
                }
                permissions.removeAllPlayerFlags(target);
                Map<String, Boolean> flags = findPlayerFlags(claim, target);
                report(result, flags == null || flags.isEmpty(),
                        "已移除成员 " + target,
                        "移除失败，请点右上角刷新查看");
                return;
            }
            case "tpset": {
                claim.setTpLoc(player, false);
                Location location = player.getLocation();
                org.bukkit.util.Vector tpLoc = claim.tpLoc;
                boolean ok = tpLoc != null
                        && Math.abs(tpLoc.getX() - location.getX()) < 1.5D
                        && Math.abs(tpLoc.getY() - location.getY()) < 1.5D
                        && Math.abs(tpLoc.getZ() - location.getZ()) < 1.5D;
                report(result, ok, "已把当前位置设为领地传送点",
                        "设置失败：请站在自己的领地范围内再试");
                return;
            }
            default:
                result.addProperty("message", "不支持的操作：" + action);
        }
    }

    /** flagLabels: 懒加载 Residence 语言文件中的「flag → 中文说明」（加载为空时下次会重试）。 */
    private Map<String, String> flagLabels() {
        if (flagLabels == null || flagLabels.isEmpty()) {
            flagLabels = loadFlagLabels();
        }
        return flagLabels;
    }

    /**
     * loadFlagLabels: 从 Residence 的语言文件里提取权限说明。
     * 直接按行做文本匹配（该文件是 CMILib 格式，交给 Bukkit 的 YAML 解析器不一定读得到），
     * 每个权限条目形如 `Translated: honeycomb - 获得蜜脾`，取破折号后的部分。
     */
    private Map<String, String> loadFlagLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        try {
            Residence residence = Residence.getInstance();
            if (residence == null) {
                return labels;
            }
            for (String language : new String[]{"Chinese.yml", "ChineseTW.yml", "English.yml"}) {
                File file = new File(residence.getDataFolder(), "Language" + File.separator + language);
                if (!file.isFile()) {
                    continue;
                }
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    Matcher matcher = TRANSLATED_LINE.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }
                    String value = stripQuotes(matcher.group(1).trim());
                    int dash = value.indexOf(" - ");
                    if (dash <= 0) {
                        continue;
                    }
                    String name = value.substring(0, dash).trim();
                    String label = value.substring(dash + 3).trim();
                    if (!label.isEmpty() && Flags.getFlag(name) != null) {
                        labels.putIfAbsent(name, label);
                    }
                }
                if (!labels.isEmpty()) {
                    break;
                }
            }
            int official = labels.size();
            if (official > 0) {
                for (Map.Entry<String, String> entry : FALLBACK_LABELS.entrySet()) {
                    labels.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            getLogger().info("权限中文说明加载完成: 官方 " + official
                    + " 条 + 补充 " + (labels.size() - official) + " 条");
            getLogger().info("可修改权限: " + possibleFlags().size() + " 项（flags.yml 的 FlagPermission）");
        } catch (Throwable throwable) {
            getLogger().warning("读取 Residence 语言文件失败: " + throwable);
        }
        return labels;
    }

    /** stripQuotes: 去掉 YAML 值两侧的引号。 */
    private static String stripQuotes(String value) {
        if (value.length() > 1) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /**
     * possibleFlags: 界面上展示的权限清单，与官方 /res set GUI 一致。
     * 直接读 Residence 的 flags.yml → Global.FlagPermission：
     * 只有值为 true 的权限在该服务器上启用（pvp、tnt、fly、backup、command 等为 false，不展示）。
     */
    private static List<String> possibleFlags() {
        List<String> names = new ArrayList<>();
        try {
            Residence residence = Residence.getInstance();
            if (residence == null) {
                return names;
            }
            File file = new File(residence.getDataFolder(), "flags.yml");
            if (!file.isFile()) {
                return names;
            }
            ConfigurationSection section =
                    YamlConfiguration.loadConfiguration(file).getConfigurationSection("Global.FlagPermission");
            if (section == null) {
                return names;
            }
            for (String key : section.getKeys(false)) {
                if (!section.getBoolean(key, false)) {
                    continue;
                }
                Flags flag = Flags.getFlag(key);
                if (flag == null) {
                    continue;
                }
                // 仅玩家级的权限（如 admin）不在领地设置界面展示，与官方 GUI 保持一致
                if (flag.getFlagMode() != null && "Player".equals(flag.getFlagMode().name())) {
                    continue;
                }
                names.add(key);
            }
        } catch (Throwable throwable) {
            return names;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** isSettableFlag: 服务端二次校验（不信任客户端传来的权限名）。 */
    private static boolean isSettableFlag(String flag) {
        return flag != null && possibleFlags().contains(flag);
    }

    private static FlagPermissions.FlagState stateOf(String value) {
        if ("true".equals(value)) {
            return FlagPermissions.FlagState.TRUE;
        }
        if ("false".equals(value)) {
            return FlagPermissions.FlagState.FALSE;
        }
        return FlagPermissions.FlagState.NEITHER;
    }

    /** trustedFlags: /res padd 使用的 trusted 权限组内容（反射读取，失败时用默认列表）。 */
    @SuppressWarnings("unchecked")
    private static Set<String> trustedFlags() {
        try {
            Field field = FlagPermissions.class.getDeclaredField("validFlagGroups");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> groups) {
                Object trusted = groups.get("trusted");
                if (trusted instanceof Set<?> set && !set.isEmpty()) {
                    Set<String> names = new LinkedHashSet<>();
                    for (Object item : set) {
                        if (item != null) {
                            names.add(item.toString());
                        }
                    }
                    return names;
                }
            }
        } catch (Throwable ignored) {
            // 反射不可用时退回内置默认值
        }
        return DEFAULT_TRUSTED_FLAGS;
    }

    private static void report(JsonObject result, boolean ok, String successMessage, String failureMessage) {
        result.addProperty("ok", ok);
        result.addProperty("message", ok ? successMessage : failureMessage);
    }

    private static boolean isBooleanText(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    /** findPlayerFlags: 按玩家名（忽略大小写）查找领地里的玩家权限表。 */
    private static Map<String, Boolean> findPlayerFlags(ClaimedResidence claim, String playerName) {
        for (Map.Entry<String, Map<String, Boolean>> entry : collectPlayerFlags(claim).entrySet()) {
            if (entry.getKey().equalsIgnoreCase(playerName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** readPayload: 解析客户端发来的 VarInt 长度 + UTF-8 JSON。 */
    private static String readPayload(byte[] message) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        int length = readVarInt(in);
        byte[] data = new byte[length];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = in.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
            if (position > 35) {
                throw new IllegalStateException("VarInt 过长");
            }
        } while ((current & 0x80) != 0);
        return value;
    }

    private JsonObject toJson(ClaimedResidence residence, boolean tp, EconomyInterface economy, boolean withFlags) {
        JsonObject object = new JsonObject();
        object.addProperty("name", residence.getName());
        object.addProperty("owner", residence.getOwner() == null ? "" : residence.getOwner());
        object.addProperty("world", residence.getWorldName() == null ? "" : residence.getWorldName());
        object.addProperty("tp", tp);
        object.addProperty("subzone", residence.isSubzone());
        object.addProperty("areas", residence.getAreaCount());
        // 大小与 /res info 一致：总体积 m³ + 水平面积 m²
        object.addProperty("size", residence.getTotalSize());
        object.addProperty("baseSize", residence.getXZSize());
        // 价值与 /res info 一致：购买价值（所有者组单价）+ 出售价格（回购单价）
        object.addProperty("worth", economy == null ? "" : economy.format(residence.getWorthByOwner()));
        object.addProperty("sellWorth", economy == null ? "" : economy.format(residence.getWorth()));
        CuboidArea area = residence.getMainArea();
        if (area != null) {
            Location low = area.getLowLoc();
            Location high = area.getHighLoc();
            if (low != null && high != null) {
                object.addProperty("minX", low.getBlockX());
                object.addProperty("minY", low.getBlockY());
                object.addProperty("minZ", low.getBlockZ());
                object.addProperty("maxX", high.getBlockX());
                object.addProperty("maxY", high.getBlockY());
                object.addProperty("maxZ", high.getBlockZ());
            }
        }
        if (withFlags) {
            object.add("flags", flagsToJson(residence));
            object.add("players", playersToJson(residence));
        }
        return object;
    }

    /** playersToJson: 领地成员（被单独授权的玩家）及其已设置的权限，供客户端管理面板使用。 */
    private JsonArray playersToJson(ClaimedResidence residence) {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, Map<String, Boolean>> entry : collectPlayerFlags(residence).entrySet()) {
            JsonObject player = new JsonObject();
            player.addProperty("name", entry.getKey());
            JsonObject playerFlags = new JsonObject();
            for (Map.Entry<String, Boolean> flag : entry.getValue().entrySet()) {
                if (flag.getKey() != null && flag.getValue() != null) {
                    playerFlags.addProperty(flag.getKey(), flag.getValue());
                }
            }
            player.add("flags", playerFlags);
            array.add(player);
        }
        return array;
    }

    /**
     * collectPlayerFlags: 合并「按名字」与「按 UUID」两张玩家权限表。
     * Residence 通过 UUID 写入玩家权限（playerFlags），按名字的表可能不含这些数据，
     * 只读其中一张会漏掉成员。
     */
    private static Map<String, Map<String, Boolean>> collectPlayerFlags(ClaimedResidence residence) {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        FlagPermissions permissions = residence.getPermissions();

        Map<String, Map<String, Boolean>> byName = permissions.getPlayerFlagsByName();
        if (byName != null) {
            for (Map.Entry<String, Map<String, Boolean>> entry : byName.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<UUID, Map<String, Boolean>> byUuid = permissions.getPlayerFlags();
        if (byUuid != null) {
            for (Map.Entry<UUID, Map<String, Boolean>> entry : byUuid.entrySet()) {
                Map<String, Boolean> flags = entry.getValue();
                if (flags == null || flags.isEmpty()) {
                    continue;
                }
                String name = resolvePlayerName(entry.getKey());
                if (name != null && !name.isBlank()) {
                    result.putIfAbsent(name, flags);
                }
            }
        }
        return result;
    }

    /** resolvePlayerName: UUID → 玩家名（在线优先，离线走 usercache）。 */
    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName();
    }

    /** flagsToJson: 读取领地各项权限的当前有效值，供客户端管理面板显示开关。 */
    private JsonObject flagsToJson(ClaimedResidence residence) {
        JsonObject flags = new JsonObject();
        FlagPermissions permissions = residence.getPermissions();
        for (Flags flag : Flags.values()) {
            flags.addProperty(flag.name(), permissions.has(flag, false));
        }
        return flags;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}