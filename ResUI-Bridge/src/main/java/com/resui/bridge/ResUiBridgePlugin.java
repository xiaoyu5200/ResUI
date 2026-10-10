package com.resui.bridge;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.ConfigManager;
import com.bekvon.bukkit.residence.chat.ChatManager;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.economy.EconomyInterface;
import com.bekvon.bukkit.residence.economy.ResidenceBank;
import com.bekvon.bukkit.residence.economy.TransactionManager;
import com.bekvon.bukkit.residence.economy.rent.RentableLand;
import com.bekvon.bukkit.residence.economy.rent.RentManager;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.LeaseManager;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.bekvon.bukkit.residence.itemlist.ItemList;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import com.bekvon.bukkit.residence.protection.PlayerManager;
import com.bekvon.bukkit.residence.selection.SelectionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.Locale;
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
    /** 合法的命令名（/res command 用，允许命名空间冒号）。 */
    private static final Pattern COMMAND_NAME = Pattern.compile("^[a-z0-9_]{1,48}(:[a-z0-9_]{1,48})?$");
    /** 合法的区域 ID（字母数字下划线，最长 24）。 */
    private static final Pattern AREA_ID = Pattern.compile("^[A-Za-z0-9_]{1,24}$");
    /** 单次扩大 / 缩小的最大格数。 */
    private static final int MAX_EXPAND = 1000;
    /** 名单 / 命令名单最多下发多少条（防包体爆炸）。 */
    private static final int MAX_LIST_ITEMS = 200;
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

    /** 插件消息通道的注册状态：注册失败（例如热重载时通道被占用）也要让插件本身启用成功。 */
    private boolean channelsRegistered;

    @Override
    public void onEnable() {
        // 通道注册：热重载/重载器场景下可能抛异常，不能让它拖垮整个插件（命令仍要可用）
        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
            // 客户端的刷新请求（自定义通道，不走命令树）；回调可能在任何线程，切到玩家区域线程再取数据
            getServer().getMessenger().registerIncomingPluginChannel(this, REFRESH_CHANNEL,
                    (channel, player, message) -> player.getScheduler().run(this, task -> sendSnapshot(player), null));
            // 客户端的写操作请求：执行 + 校验结果 + 回推最新数据
            getServer().getMessenger().registerIncomingPluginChannel(this, ACTION_CHANNEL,
                    (channel, player, message) -> player.getScheduler().run(this, task -> handleAction(player, message), null));
            channelsRegistered = true;
        } catch (Throwable throwable) {
            channelsRegistered = false;
            getLogger().severe("插件消息通道注册失败，界面将无法收发数据（/resbridge reload 后仍然失败请重启服务器）: " + throwable);
        }
        if (getCommand("resbridge") != null) {
            getCommand("resbridge").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) {
                    // Folia/Paper 通用：在玩家所属的区域线程上执行
                    player.getScheduler().run(this, task -> {
                        sendSnapshot(player);
                        player.sendMessage("§a[ResUI] 已刷新领地数据。");
                    }, null);
                } else {
                    // 控制台：没有所属区域，直接执行（不涉及实体状态，安全）
                    refreshAll();
                    sender.sendMessage("§a[ResUI] 已向所有玩家推送领地数据。");
                }
                return true;
            });
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * onDisable: 显式摘掉本插件的通道。
     * Paper 的 disablePlugin 本来就会清理（按插件实例索引，不会误伤别的插件），
     * 这里只是兜底：某些热重载器不走完整卸载流程，残留注册会让重新启用时收不到消息。
     */
    @Override
    public void onDisable() {
        if (!channelsRegistered) {
            return;
        }
        channelsRegistered = false;
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
            getServer().getMessenger().unregisterIncomingPluginChannel(this, REFRESH_CHANNEL);
            getServer().getMessenger().unregisterIncomingPluginChannel(this, ACTION_CHANNEL);
        } catch (Throwable throwable) {
            getLogger().warning("摘除插件消息通道失败: " + throwable);
        }
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

    /** refreshAll: 给所有装了 ResUI 的在线玩家各推一份最新快照（热重载后由客户端命令触发）。 */
    public void refreshAll() {
        for (Player player : getServer().getOnlinePlayers()) {
            player.getScheduler().run(this, task -> {
                if (player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
                    sendSnapshot(player);
                }
            }, null);
        }
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

        // 我租用中的领地（不是自己的，但可以查看 / 续租 / 退租）
        JsonArray rented = new JsonArray();
        try {
            RentManager rentManager = Residence.getInstance().getRentManager();
            if (rentManager != null) {
                for (ClaimedResidence residence : rentManager.getRentedLands(uuid, false)) {
                    if (residence != null && !residence.isOwner(uuid)) {
                        rented.add(toJson(residence, true, economy, false));
                    }
                }
            }
        } catch (Throwable throwable) {
            getLogger().warning("读取我租用的领地失败: " + throwable);
        }
        root.add("rented", rented);

        // 领地商城：所有**别人**挂牌出售 / 出租的领地（包含没开 tp 权限的——商城要能看见它们）
        JsonArray market = new JsonArray();
        try {
            RentManager rentManager = Residence.getInstance().getRentManager();
            if (rentManager != null) {
                for (ClaimedResidence residence : rentManager.getRentableResidences()) {
                    if (residence != null && !residence.isSubzone() && !residence.isOwner(uuid)
                            && market.size() < MAX_PUBLIC) {
                        market.add(toJson(residence, true, economy, false));
                    }
                }
            }
            TransactionManager transactions = Residence.getInstance().getTransactionManager();
            if (transactions != null) {
                for (String name : transactions.getBuyableResidences().keySet()) {
                    ClaimedResidence residence = ClaimedResidence.getByName(name);
                    if (residence != null && !residence.isSubzone() && !residence.isOwner(uuid)
                            && market.size() < MAX_PUBLIC) {
                        market.add(toJson(residence, true, economy, false));
                    }
                }
            }
        } catch (Throwable throwable) {
            getLogger().warning("读取领地商城数据失败: " + throwable);
        }
        root.add("market", market);

        root.add("own", own);
        root.add("public", publicList);
        root.addProperty("time", System.currentTimeMillis());
        // 协议 v2：玩家上下文 + 服务端功能开关（客户端据此置灰未启用的页）
        root.addProperty("proto", 2);
        root.add("me", buildMe(player));
        root.add("switches", buildSwitches());
        return root;
    }

    /** buildMe: 协议 v2 的 me 字段 —— 当前所在领地 + 我的上限（等价 /res current 与 /res limits）。 */
    private JsonObject buildMe(Player player) {
        JsonObject me = new JsonObject();
        me.addProperty("name", player.getName());
        me.addProperty("admin", player.hasPermission("residence.admin"));
        me.addProperty("current", "");
        me.addProperty("inSubzone", "");
        me.addProperty("canCreate", -1);
        me.addProperty("money", "");
        me.add("limits", new JsonObject());
        try {
            Residence residence = Residence.getInstance();
            if (residence == null) {
                return me;
            }
            // 当前所在领地：顶层领地名 + 子领地名分开给（界面侧栏要显示「父 / 子」）
            ClaimedResidence here = residence.getResidenceManager().getByLoc(player.getLocation());
            if (here != null) {
                me.addProperty("current", here.getTopParentName() == null ? here.getName() : here.getTopParentName());
                if (here.isSubzone()) {
                    me.addProperty("inSubzone", here.getName());
                }
            }
            EconomyInterface economy = residence.getEconomyManager();
            if (economy != null) {
                me.addProperty("money", economy.format(economy.getBalance(player)));
            }
            PlayerManager playerManager = residence.getPlayerManager();
            // 用 getResidencePlayer 而不是 playerJoin：后者会做「玩家加入」的副作用逻辑
            ResidencePlayer residencePlayer = playerManager == null ? null : playerManager.getResidencePlayer(player);
            PermissionGroup group = residencePlayer == null ? null : residencePlayer.getGroup();
            if (group == null) {
                return me;
            }
            int usedResidences = residencePlayer.getResAmount();
            JsonObject limits = new JsonObject();
            limits.addProperty("group", group.getGroupName());
            limits.addProperty("maxResidences", residencePlayer.getMaxRes());
            limits.addProperty("maxSubzones", group.getMaxSubzones());
            limits.addProperty("maxAreaX", group.getMaxX());
            limits.addProperty("maxAreaY", group.getMaxYSize());
            limits.addProperty("maxAreaZ", group.getMaxZ());
            limits.addProperty("maxPhysical", group.getMaxPhysicalPerResidence());
            limits.addProperty("maxSubzoneDepth", group.getMaxSubzoneDepth());
            limits.addProperty("maxRents", group.getMaxRents());
            limits.addProperty("maxRentDays", group.getMaxRentDays());
            limits.addProperty("usedResidences", usedResidences);
            limits.addProperty("canSetMessage", group.canSetEnterLeaveMessages());
            me.add("limits", limits);
            int maxRes = residencePlayer.getMaxRes();
            me.addProperty("canCreate", maxRes < 0 ? -1 : Math.max(0, maxRes - usedResidences));
        } catch (Throwable throwable) {
            getLogger().warning("读取玩家上限失败: " + throwable);
        }
        return me;
    }

    /** buildSwitches: 协议 v2 的 switches 字段 —— 直接读 Residence 的 config.yml 开关。 */
    private JsonObject buildSwitches() {
        JsonObject switches = new JsonObject();
        // 默认值按「可用」给，读不到 config 时不至于让界面全灰
        switches.addProperty("economy", true);
        switches.addProperty("rentSystem", true);
        switches.addProperty("leaseSystem", false);
        switches.addProperty("bank", true);
        switches.addProperty("residenceChat", true);
        switches.addProperty("raid", false);
        switches.addProperty("bankCapacity", 0.0D);
        try {
            Residence residence = Residence.getInstance();
            if (residence == null) {
                return switches;
            }
            ConfigManager config = residence.getConfigManager();
            if (config == null) {
                return switches;
            }
            switches.addProperty("economy", config.enableEconomy());
            switches.addProperty("rentSystem", config.enabledRentSystem());
            switches.addProperty("leaseSystem", config.useLeases());
            switches.addProperty("bank", config.enableEconomy());
            switches.addProperty("residenceChat", config.chatEnabled());
            switches.addProperty("bankCapacity", config.BankCapacity);
        } catch (Throwable throwable) {
            getLogger().warning("读取 Residence 功能开关失败: " + throwable);
        }
        return switches;
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
            // 不需要指定领地的操作：me 上下文（当前所在 / 我的上限）随下一次快照自然刷新
            if ("current".equals(action) || "limits".equals(action)) {
                result.addProperty("ok", true);
                result.addProperty("message", "current".equals(action) ? "已刷新当前位置" : "已刷新我的上限");
                sendSnapshot(player, result);
                return;
            }
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
            case "set_message": {
                // 进入 / 离开消息：与 /res message 一致，文本为空即清除
                String kind = string(request, "kind");
                String text = string(request, "text");
                boolean enter = "enter".equals(kind);
                if (!enter && !"leave".equals(kind)) {
                    result.addProperty("message", "消息类型不合法");
                    return;
                }
                if (text.length() > 256) {
                    result.addProperty("message", "消息过长（最多 256 字）");
                    return;
                }
                // 与 /res message 走同一条路径（会做消息格式校验并提示结果）
                claim.setEnterLeaveMessage(player, text, enter, true);
                String stored = enter ? claim.getEnterMessage() : claim.getLeaveMessage();
                boolean ok = text.isEmpty() ? (stored == null || stored.isEmpty()) : text.equals(stored);
                report(result, ok,
                        text.isEmpty() ? "已清除" + (enter ? "进入" : "离开") + "消息"
                                : "已设置" + (enter ? "进入" : "离开") + "消息",
                        "设置未生效，请点右上角刷新查看");
                return;
            }
            case "list_add":
            case "list_remove": {
                // 黑名单 / 忽略名单：与 /res lset 一致（按物品）
                String list = string(request, "list");
                String materialName = string(request, "material");
                boolean blacklist = "blacklist".equals(list);
                if (!blacklist && !"ignorelist".equals(list)) {
                    result.addProperty("message", "名单类型不合法");
                    return;
                }
                Material material = materialName == null ? null
                        : Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
                if (material == null || !material.isItem()) {
                    result.addProperty("message", "不是有效物品：" + materialName);
                    return;
                }
                ItemList itemList = blacklist ? claim.getItemBlacklist() : claim.getItemIgnoreList();
                if (itemList == null) {
                    result.addProperty("message", "该名单不可用");
                    return;
                }
                if ("list_add".equals(action)) {
                    itemList.add(material);
                } else {
                    itemList.remove(material);
                }
                boolean added = itemList.contains(material);
                boolean ok = "list_add".equals(action) == added;
                report(result, ok,
                        (added ? "已加入" : "已移出") + (blacklist ? "黑名单" : "忽略名单") + "：" + material.name(),
                        "未生效，请点右上角刷新查看");
                return;
            }
            // ---------------------------------------------------------- 阶段 2：区域与子领地
            case "area_add":
            case "area_replace": {
                // 等价 /res area add|replace：用玩家当前选区
                String id = string(request, "id").trim();
                if (!AREA_ID.matcher(id).matches()) {
                    result.addProperty("message", "区域名不合法（1-24 位字母数字下划线）");
                    return;
                }
                SelectionManager selection = Residence.getInstance().getSelectionManager();
                if (!selection.hasPlacedBoth(player)) {
                    result.addProperty("message", "请先用选区工具（/res tool 或木锄）点选两个对角");
                    return;
                }
                CuboidArea area = selection.getSelectionCuboid(player);
                if (area == null) {
                    result.addProperty("message", "选区无效");
                    return;
                }
                boolean ok = "area_add".equals(action)
                        ? claim.addArea(player, area, id, false)
                        : claim.replaceArea(player, area, id, false);
                boolean exists = claim.getAreaMap() != null && claim.getAreaMap().containsKey(id);
                report(result, ok && exists,
                        ("area_add".equals(action) ? "已添加区域 " : "已用当前选区替换区域 ") + id,
                        "操作未生效：区域名可能已存在、选区与领地冲突或超出权限组的尺寸上限");
                return;
            }
            case "area_remove": {
                String id = string(request, "id").trim();
                if (id.isEmpty() || claim.getAreaMap() == null || !claim.getAreaMap().containsKey(id)) {
                    result.addProperty("message", "区域不存在：" + id);
                    return;
                }
                if (claim.getAreaCount() <= 1) {
                    result.addProperty("message", "领地至少要保留一个区域");
                    return;
                }
                claim.removeArea(player, id, false);
                report(result, claim.getAreaMap() == null || !claim.getAreaMap().containsKey(id),
                        "已删除区域 " + id,
                        "删除失败：该区域可能是主区域，或与子领地冲突");
                return;
            }
            case "area_rename": {
                String id = string(request, "id").trim();
                String newId = string(request, "newId").trim();
                if (newId.isEmpty() || !AREA_ID.matcher(newId).matches()) {
                    result.addProperty("message", "新区域名不合法（1-24 位字母数字下划线）");
                    return;
                }
                boolean ok = claim.renameArea(player, id, newId, false);
                report(result, ok && claim.getAreaMap() != null && claim.getAreaMap().containsKey(newId),
                        "已把区域 " + id + " 重命名为 " + newId,
                        "重命名失败：原区域不存在或新名字已被占用");
                return;
            }
            case "area_setmain": {
                // 主区域在 Residence 里就是用键名 "main" 标记的（CuboidArea 本身没有名字字段），
                // 所以「设为主区域」= 把该区域的键重命名成 main（先把原 main 挪走，避免重名冲突）。
                String id = string(request, "id").trim();
                Map<String, CuboidArea> areas = claim.getAreaMap();
                if (areas == null || !areas.containsKey(id)) {
                    result.addProperty("message", "区域不存在：" + id);
                    return;
                }
                if ("main".equals(id)) {
                    result.addProperty("ok", true);
                    result.addProperty("message", "它已经是主区域了");
                    return;
                }
                String temp = "tmp_" + System.currentTimeMillis() % 100000;
                if (areas.containsKey("main") && !claim.renameArea(player, "main", temp, false)) {
                    result.addProperty("message", "无法腾出主区域（重命名 main 失败）");
                    return;
                }
                if (!claim.renameArea(player, id, "main", false)) {
                    // 失败就把刚挪走的 main 还原，别留下半成品状态
                    claim.renameArea(player, temp, "main", false);
                    result.addProperty("message", "设为主区域失败");
                    return;
                }
                claim.renameArea(player, temp, id, false);
                // 与 /res setmain 一致：同时把这块领地标记为玩家的主领地
                try {
                    ResidencePlayer residencePlayer = Residence.getInstance().getPlayerManager()
                            .getResidencePlayer(player);
                    if (residencePlayer != null) {
                        residencePlayer.setMainResidence(claim);
                    }
                } catch (Throwable throwable) {
                    getLogger().warning("记录玩家主领地失败（不影响区域设置）: " + throwable);
                }
                report(result, claim.getAreaMap() != null && claim.getAreaMap().containsKey("main"),
                        "已把 " + id + " 设为主区域（原主区域改名为 " + id + "）",
                        "设置未生效，请点右上角刷新查看");
                return;
            }
            case "tp_area": {
                String id = string(request, "id").trim();
                CuboidArea area = claim.getAreaMap() == null ? null : claim.getAreaMap().get(id);
                if (area == null) {
                    result.addProperty("message", "区域不存在：" + id);
                    return;
                }
                Location center = area.getLowLoc().clone().add(area.getHighLoc()).multiply(0.5D);
                center.setYaw(player.getLocation().getYaw());
                center.setPitch(player.getLocation().getPitch());
                // 异步传送，避免在主线程做跨区域传送（Folia 友好）
                player.teleportAsync(center);
                result.addProperty("ok", true);
                result.addProperty("message", "已传送到区域 " + id);
                return;
            }
            case "subzone_create": {
                // 等价 /res subzone：用玩家当前选区，在父领地上创建子领地
                String name = string(request, "sub").trim();
                if (!Residence.getInstance().validName(name)) {
                    result.addProperty("message", "子领地名字不合法");
                    return;
                }
                SelectionManager selection = Residence.getInstance().getSelectionManager();
                if (!selection.hasPlacedBoth(player)) {
                    result.addProperty("message", "请先用选区工具点选两个对角");
                    return;
                }
                Location low = selection.getPlayerLoc1(player);
                Location high = selection.getPlayerLoc2(player);
                if (low == null || high == null) {
                    result.addProperty("message", "选区无效");
                    return;
                }
                // 前置自查（addSubzone 只返回 boolean，失败原因需要自己给）
                if (claim.getSubzone(name) != null) {
                    result.addProperty("message", "该领地已经有同名子领地：" + name);
                    return;
                }
                if (claim.getSubzoneList() != null) {
                    ResidencePlayer owner = Residence.getInstance().getPlayerManager().getResidencePlayer(player);
                    int max = owner == null ? -1 : owner.getMaxSubzones();
                    if (max >= 0 && claim.getSubzoneList().length + 1 > max) {
                        result.addProperty("message", "子领地数量已达上限（" + max + "）");
                        return;
                    }
                }
                boolean ok = claim.addSubzone(player, low, high, name, false);
                report(result, ok && claim.getSubzone(name) != null,
                        "已创建子领地 " + name,
                        "创建失败：两个选区点都必须在这块领地范围内，且子领地不能与已有领地冲突");
                return;
            }
            case "subzone_remove": {
                // 删除子领地本身（在父领地上按名字调用）
                ClaimedResidence parent = claim.getParent();
                if (parent == null) {
                    result.addProperty("message", "这不是子领地");
                    return;
                }
                String name = claim.getName();
                boolean ok = parent.removeSubzone(player, name, false);
                report(result, ok && parent.getSubzone(name) == null,
                        "已删除子领地 " + name,
                        "删除失败：服务器可能禁止删除子领地（PreventSubZoneRemoval）或子领地正在出租");
                return;
            }

            // ---------------------------------------------------------- 阶段 3：危险操作（客户端已二次确认）
            case "rename": {
                String newName = string(request, "newName").trim();
                if (!Residence.getInstance().validName(newName)) {
                    result.addProperty("message", "新名字不合法");
                    return;
                }
                if (claim.isSubzone()) {
                    // 子领地要用 renameSubzone（在父领地上按本层名字调用）
                    ClaimedResidence parent = claim.getParent();
                    boolean ok = parent != null && parent.renameSubzone(player, claim.getName(), newName, false);
                    report(result, ok, "已把子领地重命名为 " + newName, "重命名失败：名字可能已被占用");
                    return;
                }
                boolean ok = Residence.getInstance().getResidenceManager()
                        .renameResidence(player, claim.getName(), newName, false);
                report(result, ok && ClaimedResidence.getByName(newName) != null,
                        "已把领地重命名为 " + newName,
                        "重命名失败：名字可能已被占用或包含非法字符");
                return;
            }
            case "expand":
            case "contract": {
                // 与 /res expand|contract 等价：对目标区域整体外扩 / 内缩
                int amount = parseInt(request, "amount");
                if (amount <= 0 || amount > MAX_EXPAND) {
                    result.addProperty("message", "数量必须在 1 ~ " + MAX_EXPAND + " 之间");
                    return;
                }
                String areaId = string(request, "id").trim();
                Map<String, CuboidArea> areas = claim.getAreaMap();
                if (areas == null || areas.isEmpty()) {
                    result.addProperty("message", "领地没有区域");
                    return;
                }
                if (areaId.isEmpty()) {
                    areaId = mainAreaName(claim);
                }
                CuboidArea area = areaId == null ? null : areas.get(areaId);
                if (area == null) {
                    result.addProperty("message", "区域不存在：" + areaId);
                    return;
                }
                Location low = area.getLowLoc().clone();
                Location high = area.getHighLoc().clone();
                int delta = "expand".equals(action) ? amount : -amount;
                low.add(-delta, -delta, -delta);
                high.add(delta, delta, delta);
                if ("contract".equals(action)
                        && (low.getBlockX() > high.getBlockX() || low.getBlockY() > high.getBlockY()
                        || low.getBlockZ() > high.getBlockZ())) {
                    result.addProperty("message", "缩小幅度过大：会把区域缩成负数尺寸");
                    return;
                }
                if (low.getBlockY() < player.getWorld().getMinHeight()
                        || high.getBlockY() > player.getWorld().getMaxHeight()) {
                    result.addProperty("message", "超出世界高度限制（"
                            + player.getWorld().getMinHeight() + " ~ " + player.getWorld().getMaxHeight() + "）");
                    return;
                }
                CuboidArea updated = new CuboidArea(low, high);
                boolean ok = claim.replaceArea(player, updated, areaId, false);
                CuboidArea after = claim.getAreaMap() == null ? null : claim.getAreaMap().get(areaId);
                report(result, ok && after != null
                                && after.getLowLoc().getBlockX() == low.getBlockX()
                                && after.getHighLoc().getBlockX() == high.getBlockX(),
                        ("expand".equals(action) ? "已扩大 " : "已缩小 ") + amount + " 格（区域 " + areaId + "）",
                        "操作未生效：可能与别的领地冲突或超出权限组的尺寸上限");
                return;
            }
            case "give": {
                if (!isConfirmed(request)) {
                    result.addProperty("message", "需要二次确认");
                    return;
                }
                String target = string(request, "player").trim();
                if (!PLAYER_NAME.matcher(target).matches()) {
                    result.addProperty("message", "玩家名不合法");
                    return;
                }
                OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(target);
                if (offline == null || offline.getUniqueId() == null) {
                    result.addProperty("message", "找不到该玩家（需要他至少进过一次服务器）：" + target);
                    return;
                }
                if (offline.getUniqueId().equals(claim.getOwnerUUID())) {
                    result.addProperty("message", "他已经是这块领地的主人了");
                    return;
                }
                // keepFlags=true：保留原权限（不要用 giveResidence，它内部恒为 applyDefaultFlags 覆盖）
                claim.getPermissions().setOwner(offline.getUniqueId(), false);
                boolean ok = offline.getUniqueId().equals(claim.getOwnerUUID());
                report(result, ok,
                        "已把领地转让给 " + target + "（权限保持不变）",
                        "转让未生效：可能被服务器的领地变更事件取消");
                return;
            }
            case "remove": {
                if (!isConfirmed(request)) {
                    result.addProperty("message", "需要二次确认");
                    return;
                }
                String name = claim.getName();
                if (claim.isSubzone()) {
                    ClaimedResidence parent = claim.getParent();
                    boolean ok = parent != null && parent.removeSubzone(player, name, false);
                    report(result, ok && parent != null && parent.getSubzone(name) == null,
                            "已删除子领地 " + name, "删除失败，请点右上角刷新查看");
                    return;
                }
                // 用「玩家」重载，保证走权限校验（不要用 CommandSender 重载：sender 不是玩家时会绕过权限）
                Residence.getInstance().getResidenceManager().removeResidence(player, claim, true);
                report(result, ClaimedResidence.getByName(name) == null,
                        "已删除领地 " + name,
                        "删除失败：可能被服务器的领地删除事件取消，或子领地还在出租中");
                return;
            }

            // ---------------------------------------------------------- 阶段 4：市场与出租
            case "market_sell": {
                if (!economyEnabled()) {
                    result.addProperty("message", "服务器未启用经济系统");
                    return;
                }
                int price = parseInt(request, "price");
                if (price <= 0) {
                    result.addProperty("message", "售价必须是大于 0 的整数");
                    return;
                }
                TransactionManager transactions = Residence.getInstance().getTransactionManager();
                if (claim.isForSell()) {
                    result.addProperty("message", "这块领地已经在出售中了（先撤下再改价）");
                    return;
                }
                if (claim.isForRent() || claim.isRented()) {
                    result.addProperty("message", "领地在出租中，不能同时出售（Residence 限制）");
                    return;
                }
                transactions.putForSale(claim, player, price, false);
                // 成功以领地的活状态为准（claim.isForSell()），价格只用于回执文案
                report(result, claim.isForSell(),
                        "已挂牌出售，售价 " + money(price),
                        "上架失败：可能被 Residence 的配置或事件拦下");
                return;
            }
            case "market_unsell": {
                TransactionManager transactions = Residence.getInstance().getTransactionManager();
                if (!claim.isForSell()) {
                    result.addProperty("message", "这块领地没有在出售");
                    return;
                }
                transactions.removeFromSale(player, claim, false);
                report(result, !claim.isForSell(), "已从市场撤下", "撤下失败，请点右上角刷新查看");
                return;
            }
            case "market_buy": {
                if (!isConfirmed(request)) {
                    result.addProperty("message", "需要二次确认");
                    return;
                }
                if (!economyEnabled()) {
                    result.addProperty("message", "服务器未启用经济系统");
                    return;
                }
                TransactionManager transactions = Residence.getInstance().getTransactionManager();
                if (!claim.isForSell()) {
                    result.addProperty("message", "这块领地没有在出售");
                    return;
                }
                if (claim.isOwner(player)) {
                    result.addProperty("message", "这是你自己的领地");
                    return;
                }
                int price = transactions.getSaleAmount(claim);
                // 钱与所有权都由 Residence 的 buyPlot 内部处理（不要在这里自己扣款，会双重扣费）
                transactions.buyPlot(claim, player, false);
                report(result, claim.isOwner(player),
                        "已购得领地（花费 " + money(price) + "）",
                        "购买失败：余额不足或被服务器拦下");
                return;
            }
            case "market_rentable": {
                if (!rentEnabled()) {
                    result.addProperty("message", "服务器未启用出租系统");
                    return;
                }
                int days = parseInt(request, "days");
                int cost = parseInt(request, "cost");
                if (days <= 0 || cost <= 0) {
                    result.addProperty("message", "租期与租金都必须是大于 0 的整数");
                    return;
                }
                RentManager rents = Residence.getInstance().getRentManager();
                if (claim.isRented()) {
                    result.addProperty("message", "这块领地正在被租用，不能改出租设置");
                    return;
                }
                boolean allowRenewing = boolOf(request, "renew", true);
                boolean stayInMarket = boolOf(request, "stay", true);
                boolean allowAutoPay = boolOf(request, "autopay", true);
                if (claim.isForRent()) {
                    rents.removeFromForRent(player, claim, false);
                }
                // 参数顺序（从字节码核实，别按名字猜）：
                // (player, residence, cost, days, AllowRenewing, StayInMarket, AllowAutoPay, ignorePermissions)
                rents.setForRent(player, claim, cost, days, allowRenewing, stayInMarket, allowAutoPay, false);
                // 成功判定以领地的活状态为准（claim.isForRent()），价格/天数只用来拼回执文案。
                // 不要用「立即回读 getRentableLand() 的字段是否相等」当成功条件：实测会出现
                // setForRent 已生效（Residence 自己都在聊天栏提示上架成功）但紧跟的回读拿不到值，
                // 那会把成功报成失败。真正的价格/天数会随下一次快照回传到界面上。
                report(result, claim.isForRent(),
                        "已挂牌出租：" + money(cost) + " / " + days + " 天",
                        "上架失败：可能超出权限组的可出租数量或租金上限");
                return;
            }
            case "market_unrentable": {
                RentManager rents = Residence.getInstance().getRentManager();
                if (!claim.isForRent()) {
                    result.addProperty("message", "这块领地没有在出租");
                    return;
                }
                rents.removeFromForRent(player, claim, false);
                report(result, !claim.isForRent(), "已从市场撤下出租", "撤下失败，请点右上角刷新查看");
                return;
            }
            case "market_rent": {
                if (!isConfirmed(request)) {
                    result.addProperty("message", "需要二次确认");
                    return;
                }
                if (!rentEnabled()) {
                    result.addProperty("message", "服务器未启用出租系统");
                    return;
                }
                RentManager rents = Residence.getInstance().getRentManager();
                if (!claim.isForRent()) {
                    result.addProperty("message", "这块领地没有在出租");
                    return;
                }
                if (claim.isOwner(player)) {
                    result.addProperty("message", "这是你自己的领地");
                    return;
                }
                if (claim.isRented()) {
                    result.addProperty("message", "已经被别人租走了");
                    return;
                }
                boolean autoPay = boolOf(request, "autopay", false);
                // 租金与期限由 Residence 的 rent 内部处理
                rents.rent(player, claim, false, false);
                if (autoPay && claim.isRented()) {
                    rents.setRentedRepeatable(player, claim, true, false);
                }
                report(result, claim.isRented(),
                        "已租下这块领地（" + rents.getRentDays(claim) + " 天，租金 "
                                + money(rents.getCostOfRent(claim)) + "）",
                        "租用失败：余额不足或已被租走");
                return;
            }
            case "market_unrent": {
                RentManager rents = Residence.getInstance().getRentManager();
                if (!claim.isRented()) {
                    result.addProperty("message", "这块领地当前没有被租用");
                    return;
                }
                rents.unrent(player, claim, false);
                report(result, !claim.isRented(), "已退租", "退租失败，请点右上角刷新查看");
                return;
            }
            case "market_autopay": {
                RentManager rents = Residence.getInstance().getRentManager();
                if (!claim.isRented()) {
                    result.addProperty("message", "这块领地当前没有被租用");
                    return;
                }
                boolean value = boolOf(request, "value", false);
                rents.setRentedRepeatable(player, claim, value, false);
                report(result, rents.getRentedAutoRepeats(claim) == value,
                        value ? "已开启自动续租" : "已关闭自动续租",
                        "设置未生效，请点右上角刷新查看");
                return;
            }
            case "market_payrent": {
                RentManager rents = Residence.getInstance().getRentManager();
                if (!claim.isRented()) {
                    result.addProperty("message", "这块领地当前没有被租用");
                    return;
                }
                rents.payRent(player, claim, false);
                result.addProperty("ok", true);
                result.addProperty("message", "已支付本期租金，到期时间已顺延");
                return;
            }
            case "lease_renew": {
                if (!leaseEnabled()) {
                    result.addProperty("message", "服务器未启用租约系统");
                    return;
                }
                LeaseManager leases = Residence.getInstance().getLeaseManager();
                if (leases == null || !leases.isLeased(claim)) {
                    result.addProperty("message", "这块领地没有租约");
                    return;
                }
                double cost = leases.getRenewCostD(claim);
                EconomyInterface leasesEconomy = Residence.getInstance().getEconomyManager();
                if (leasesEconomy != null && !leasesEconomy.canAfford(player, cost)) {
                    result.addProperty("message", "余额不足，续租需要 " + money((int) cost));
                    return;
                }
                leases.renewArea(claim, player);
                result.addProperty("ok", true);
                result.addProperty("message", "已续租，花费 " + money((int) cost));
                return;
            }

            // ---------------------------------------------------------- 阶段 5：领地银行
            case "bank_deposit":
            case "bank_withdraw": {
                if (!economyEnabled()) {
                    result.addProperty("message", "服务器未启用经济系统");
                    return;
                }
                int amount = parseInt(request, "amount");
                if (amount <= 0) {
                    result.addProperty("message", "金额必须是大于 0 的整数");
                    return;
                }
                ResidenceBank bank = claim.getBank();
                if (bank == null) {
                    result.addProperty("message", "这块领地的银行不可用");
                    return;
                }
                double before = bank.getStoredMoneyD();
                boolean deposit = "bank_deposit".equals(action);
                // 存取都由 ResidenceBank 内部处理（它会查 Flags.bank、余额、BankCapacity）
                if (deposit) {
                    bank.deposit(player, amount, false);
                } else {
                    bank.withdraw(player, amount, false);
                }
                double after = bank.getStoredMoneyD();
                boolean ok = deposit ? after > before : after < before;
                report(result, ok,
                        "已" + (deposit ? "存入 " : "取出 ") + money(amount) + "，当前余额 " + bank.getStoredMoneyFormated(),
                        "未生效：请检查余额、领地 bank 权限或银行容量上限（当前余额 "
                                + bank.getStoredMoneyFormated() + "）");
                return;
            }

            // ---------------------------------------------------------- 阶段 5：领地聊天
            case "chat_join":
            case "chat_leave": {
                if (!chatEnabled()) {
                    result.addProperty("message", "服务器未启用领地聊天");
                    return;
                }
                ChatManager chat = Residence.getInstance().getChatManager();
                boolean joined = chat != null && chat.getPlayerChannel(player.getUniqueId()) != null;
                if ("chat_leave".equals(action)) {
                    if (!joined) {
                        result.addProperty("message", "你不在领地聊天频道里");
                        return;
                    }
                    ChatManager.removePlayerResidenceChat(player);
                    report(result, chat.getPlayerChannel(player.getUniqueId()) == null,
                            "已退出领地聊天", "退出失败，请点右上角刷新查看");
                    return;
                }
                if (joined) {
                    result.addProperty("message", "你已经在领地聊天频道里了");
                    return;
                }
                ChatManager.tooglePlayerResidenceChat(player, claim.getName());
                report(result, chat.getPlayerChannel(player.getUniqueId()) != null,
                        "已加入「" + claim.getName() + "」的领地聊天（在聊天框打字即发出）",
                        "加入失败：可能需要领地的 chat 权限");
                return;
            }
            case "chat_set": {
                String prefix = string(request, "prefix").trim();
                String color = string(request, "color").trim().toUpperCase(Locale.ROOT);
                if (prefix.length() > 16) {
                    result.addProperty("message", "前缀最长 16 个字符（含颜色代码）");
                    return;
                }
                boolean prefixOk = true;
                if (!prefix.isEmpty()) {
                    claim.setChatPrefix(prefix);
                    prefixOk = prefix.equals(claim.getChatPrefix());
                }
                boolean colorOk = true;
                if (!color.isEmpty()) {
                    colorOk = setChatColor(claim, color);
                }
                report(result, prefixOk && colorOk,
                        "已更新领地聊天设置",
                        colorOk ? "保存失败，请点右上角刷新查看"
                                : "颜色名不被识别（例如 RED、GOLD、DARK_PURPLE）");
                return;
            }
            case "chat_kick": {
                String target = string(request, "player").trim();
                if (!PLAYER_NAME.matcher(target).matches()) {
                    result.addProperty("message", "玩家名不合法");
                    return;
                }
                Player online = Bukkit.getPlayerExact(target);
                if (online == null) {
                    result.addProperty("message", "该玩家不在线：" + target);
                    return;
                }
                boolean kicked = claim.kickFromResidence(online);
                report(result, kicked, "已把 " + target + " 移出领地", "移除失败，请点右上角刷新查看");
                return;
            }

            case "command_block":
            case "command_allow":
            case "command_remove": {
                // 领地内命令放行 / 禁用：与 /res command 一致
                String cmd = string(request, "cmd").trim().toLowerCase(Locale.ROOT);
                if (cmd.isEmpty() || cmd.length() > 64 || !COMMAND_NAME.matcher(cmd).matches()) {
                    result.addProperty("message", "命令名不合法（只能是字母、数字、下划线、冒号）");
                    return;
                }
                List<String> blocked = claim.getCmdBlackList();
                List<String> allowed = claim.getCmdWhiteList();
                boolean isBlocked = blocked != null && blocked.contains(cmd);
                boolean isAllowed = allowed != null && allowed.contains(cmd);
                if ("command_remove".equals(action)) {
                    // Residence 只提供 getCmdBlackList/getCmdWhiteList 两个 getter（没有 removeCmd* 方法），
                    // 这里直接改它返回的列表；万一列表不可变则回报失败，不让异常冒到外层当「执行失败」。
                    try {
                        if (isBlocked) {
                            blocked.remove(cmd);
                        }
                        if (isAllowed) {
                            allowed.remove(cmd);
                        }
                    } catch (UnsupportedOperationException immutable) {
                        result.addProperty("message", "该服务器的命令名单不可直接修改，请用 /res command 处理");
                        return;
                    }
                    List<String> afterBlocked = claim.getCmdBlackList();
                    List<String> afterAllowed = claim.getCmdWhiteList();
                    report(result,
                            (afterBlocked == null || !afterBlocked.contains(cmd))
                                    && (afterAllowed == null || !afterAllowed.contains(cmd)),
                            "已从名单移除 " + cmd,
                            (isBlocked || isAllowed) ? "移除失败，请点右上角刷新查看" : "该命令不在名单里");
                    return;
                }
                boolean wantBlocked = "command_block".equals(action);
                if (wantBlocked == isBlocked && !isAllowed) {
                    result.addProperty("ok", true);
                    result.addProperty("message", cmd + " 已经是" + (wantBlocked ? "禁用" : "放行") + "状态");
                    return;
                }
                if (wantBlocked) {
                    // 只加不减：Residence 没有 removeCmd* 方法，直接改 getter 返回的列表有不可变风险，
                    // 而「黑名单优先、白名单失效」是 Residence 自身的判定顺序，多留一条白名单无害。
                    claim.addCmdBlackList(cmd);
                } else {
                    claim.addCmdWhiteList(cmd);
                }
                report(result, claim.getCmdBlackList().contains(cmd) == wantBlocked
                                && claim.getCmdWhiteList().contains(cmd) != wantBlocked,
                        "已" + (wantBlocked ? "禁用" : "放行") + "命令 " + cmd
                                + (wantBlocked ? "" : "（如需彻底移除，用“从名单移除”）"),
                        "未生效，请点右上角刷新查看");
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

    /** parseInt: 宽容地把字符串参数转成整数，非法值返回 0（随后会被范围校验拦下）。 */
    private static int parseInt(JsonObject json, String key) {
        try {
            return Integer.parseInt(string(json, key).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** isConfirmed: 危险操作必须显式带 confirm=true（客户端在二次确认弹窗里才加这个字段）。 */
    private static boolean isConfirmed(JsonObject request) {
        return request.has("confirm") && !request.get("confirm").isJsonNull()
                && request.get("confirm").getAsBoolean();
    }

    /** boolOf: 读一个布尔参数（支持字符串形式的 true/false），缺省时用 fallback。 */
    private static boolean boolOf(JsonObject json, String key, boolean fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return "true".equalsIgnoreCase(json.get(key).getAsString()) || fallback;
        }
    }

    /** economyEnabled: Residence 的经济系统是否开启。 */
    private static boolean economyEnabled() {
        try {
            ConfigManager config = Residence.getInstance() == null ? null : Residence.getInstance().getConfigManager();
            return config == null || config.enableEconomy();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** rentEnabled: 出租系统是否开启。 */
    private static boolean rentEnabled() {
        try {
            ConfigManager config = Residence.getInstance() == null ? null : Residence.getInstance().getConfigManager();
            return config == null || config.enabledRentSystem();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** leaseEnabled: 租约系统是否开启。 */
    private static boolean leaseEnabled() {
        try {
            ConfigManager config = Residence.getInstance() == null ? null : Residence.getInstance().getConfigManager();
            return config != null && config.useLeases();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** chatEnabled: 领地聊天是否开启。 */
    private static boolean chatEnabled() {
        try {
            ConfigManager config = Residence.getInstance() == null ? null : Residence.getInstance().getConfigManager();
            return config == null || config.chatEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** money: 用服务器的货币名格式化金额（经济不可用时退回纯数字）。 */
    private static String money(int amount) {
        try {
            EconomyInterface economy = Residence.getInstance() == null
                    ? null : Residence.getInstance().getEconomyManager();
            return economy == null ? String.valueOf(amount) : economy.format(amount);
        } catch (Throwable ignored) {
            return String.valueOf(amount);
        }
    }

    /**
     * setChatColor: 设置领地聊天频道颜色。
     * setChannelColor 的参数是 CMIChatColor（来自 CMILib，不在编译依赖里），所以反射调用；
     * 颜色对象本身由 CMIChatColor.getByName(String) 静态方法解析。
     */
    private static boolean setChatColor(ClaimedResidence residence, String colorName) {
        try {
            Class<?> colorClass = Class.forName("net.Zrips.CMILib.Colors.CMIChatColor");
            Object color = colorClass.getMethod("getByName", String.class).invoke(null, colorName);
            if (color == null) {
                return false;
            }
            ClaimedResidence.class.getMethod("setChannelColor", colorClass).invoke(residence, color);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
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
        // ---- 协议 v2：子领地 / 进出消息 / 名单 / 命令名单（大数组只对自己的领地下发）----
        object.addProperty("parent", residence.isSubzone() && residence.getParent() != null
                ? residence.getParent().getName() : "");
        object.add("messages", messagesToJson(residence));
        object.add("subzones", withFlags ? subzonesToJson(residence) : new JsonArray());
        object.add("areaList", withFlags ? areasToJson(residence) : new JsonArray());
        // 市场 / 出租 / 租约：出售与出租信息对所有人可见（市场页要展示别人挂牌的领地）
        marketToJson(object, residence, economy);
        if (withFlags) {
            object.add("blacklist", itemsToJson(residence.getItemBlacklist()));
            object.add("ignorelist", itemsToJson(residence.getItemIgnoreList()));
            object.add("blockedCommands", commandsToJson(residence));
            object.add("chat", chatToJson(residence));
        }
        return object;
    }

    /**
     * marketToJson: 出售 / 出租 / 租约状态。
     * 注意：售价与租金都是 int（Residence 内部就是整数货币），所以直接用经济接口格式化。
     */
    private void marketToJson(JsonObject object, ClaimedResidence residence, EconomyInterface economy) {
        object.addProperty("forSale", residence.isForSell());
        object.addProperty("sellPrice", residence.getSellPrice() == null ? 0 : residence.getSellPrice());
        object.addProperty("forSaleText",
                residence.isForSell() && economy != null
                        ? economy.format(residence.getSellPrice() == null ? 0 : residence.getSellPrice())
                        : "");
        boolean forRent = residence.isForRent();
        boolean rented = residence.isRented();
        object.addProperty("forRent", forRent || residence.isSubzoneForRent() || residence.isParentForRent());
        object.addProperty("rented", rented || residence.isSubzoneRented());
        object.addProperty("rentableSubzone", subzoneNameIfRentable(residence));
        object.addProperty("rentRentedSubzone", rentedSubzoneName(residence));
        RentableLand rentable = null;
        try {
            rentable = Residence.getInstance().getRentManager().getRentableLand(residence);
        } catch (Throwable throwable) {
            getLogger().warning("读取出租信息失败: " + throwable);
        }
        if (rentable != null) {
            object.addProperty("rentDays", rentable.days);
            object.addProperty("rentCost", rentable.cost);
            object.addProperty("rentCostText", economy == null ? String.valueOf(rentable.cost) : economy.format(rentable.cost));
            object.addProperty("rentAllowRenewing", rentable.AllowRenewing);
            object.addProperty("rentStayInMarket", rentable.StayInMarket);
            object.addProperty("rentAllowAutoPay", rentable.AllowAutoPay);
        }
        RentedLand rentedLand = null;
        try {
            rentedLand = Residence.getInstance().getRentManager().getRentedLand(residence);
        } catch (Throwable throwable) {
            getLogger().warning("读取租用信息失败: " + throwable);
        }
        if (rentedLand != null) {
            object.addProperty("renter", rentedLand.getRenterName() == null ? "" : rentedLand.getRenterName());
            object.addProperty("rentEndTime", rentedLand.endTime);
            object.addProperty("rentAutoPay", rentedLand.AutoPay);
            object.addProperty("rentExpireText", expireText(rentedLand.endTime));
        }
        // 租约（/res lease，与「出租」是两套系统）
        try {
            LeaseManager leases = Residence.getInstance().getLeaseManager();
            object.addProperty("leaseExpireText", leases == null ? "" : leases.getExpireTime(residence));
            object.addProperty("leaseRenewCostText", leases == null || economy == null
                    ? "" : economy.format(leases.getRenewCostD(residence)));
        } catch (Throwable throwable) {
            getLogger().warning("读取租约信息失败: " + throwable);
        }
        object.addProperty("bank", residence.getBank() == null ? "" : residence.getBank().getStoredMoneyFormated());
        object.addProperty("bankRaw", residence.getBank() == null ? 0.0D : residence.getBank().getStoredMoneyD());
    }

    /** subzoneNameIfRentable: 若出租的是子领地，返回那个子领地的名字（父领地挂租时用）。 */
    private static String subzoneNameIfRentable(ClaimedResidence residence) {
        try {
            if (residence.isSubzoneForRent()) {
                ClaimedResidence subzone = residence.getRentedSubzone();
                return subzone == null ? "" : subzone.getName();
            }
        } catch (Throwable ignored) {
            // 忽略：字段只是展示用
        }
        return "";
    }

    /** rentedSubzoneName: 该领地当前被租出去的子领地名字。 */
    private static String rentedSubzoneName(ClaimedResidence residence) {
        try {
            if (residence.isSubzoneRented()) {
                ClaimedResidence subzone = residence.getRentedSubzone();
                return subzone == null ? "" : subzone.getName();
            }
        } catch (Throwable ignored) {
            // 忽略：字段只是展示用
        }
        return "";
    }

    /** expireText: 租用到期时间的人话文案。 */
    private static String expireText(long endTime) {
        if (endTime <= 0L) {
            return "";
        }
        long remainMillis = endTime - System.currentTimeMillis();
        if (remainMillis <= 0L) {
            return "已到期";
        }
        long days = remainMillis / 86_400_000L;
        long hours = (remainMillis % 86_400_000L) / 3_600_000L;
        return days > 0 ? days + " 天 " + hours + " 小时后到期" : hours + " 小时后到期";
    }

    /** messagesToJson: 进入 / 离开提示消息（未设置时为空串）。 */
    private JsonObject messagesToJson(ClaimedResidence residence) {
        JsonObject messages = new JsonObject();
        messages.addProperty("enter", residence.getEnterMessage() == null ? "" : residence.getEnterMessage());
        messages.addProperty("leave", residence.getLeaveMessage() == null ? "" : residence.getLeaveMessage());
        return messages;
    }

    /** subzonesToJson: 子领地简要列表（名称 / 所有者 / 区域数 / 体积），供管理面板画树。 */
    private JsonArray subzonesToJson(ClaimedResidence residence) {
        JsonArray array = new JsonArray();
        List<ClaimedResidence> subzones = residence.getSubzones();
        if (subzones == null) {
            return array;
        }
        for (ClaimedResidence subzone : subzones) {
            JsonObject item = new JsonObject();
            item.addProperty("name", subzone.getName());
            item.addProperty("owner", subzone.getOwner() == null ? "" : subzone.getOwner());
            item.addProperty("areas", subzone.getAreaCount());
            item.addProperty("size", subzone.getTotalSize());
            item.addProperty("rented", subzone.isRented());
            item.addProperty("forRent", subzone.isForRent());
            array.add(item);
        }
        return array;
    }

    /** areasToJson: 该领地的全部区域（cuboid）明细，主区域带 main 标记。 */
    private JsonArray areasToJson(ClaimedResidence residence) {
        JsonArray array = new JsonArray();
        Map<String, CuboidArea> areas = residence.getAreaMap();
        if (areas == null) {
            return array;
        }
        String mainName = mainAreaName(residence);
        for (Map.Entry<String, CuboidArea> entry : areas.entrySet()) {
            CuboidArea area = entry.getValue();
            if (area == null || area.getLowLoc() == null || area.getHighLoc() == null) {
                continue;
            }
            Location low = area.getLowLoc();
            Location high = area.getHighLoc();
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("minX", low.getBlockX());
            item.addProperty("minY", low.getBlockY());
            item.addProperty("minZ", low.getBlockZ());
            item.addProperty("maxX", high.getBlockX());
            item.addProperty("maxY", high.getBlockY());
            item.addProperty("maxZ", high.getBlockZ());
            item.addProperty("size", (long) (high.getBlockX() - low.getBlockX() + 1)
                    * (high.getBlockY() - low.getBlockY() + 1)
                    * (high.getBlockZ() - low.getBlockZ() + 1));
            item.addProperty("main", entry.getKey().equals(mainName));
            array.add(item);
        }
        return array;
    }

    /**
     * chatColorName: 频道颜色的名字。
     * getChannelColor() 的返回类型 CMIChatColor 来自 CMILib（不在本插件的编译依赖里），
     * 直接调用会因为「无法访问 CMIChatColor」编译失败，所以走「取 Method 再反射调用」。
     */
    private static String chatColorName(ClaimedResidence residence) {
        try {
            java.lang.reflect.Method getter = ClaimedResidence.class.getMethod("getChannelColor");
            Object color = getter.invoke(residence);
            if (color == null) {
                return "";
            }
            java.lang.reflect.Method name = color.getClass().getMethod("name");
            Object value = name.invoke(color);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** mainAreaName: 主区域在 areaMap 里的 key（CuboidArea 自身没有名字，名字就是 map 的键）。 */
    private static String mainAreaName(ClaimedResidence residence) {
        CuboidArea main = residence.getMainArea();
        Map<String, CuboidArea> areas = residence.getAreaMap();
        if (main == null || areas == null) {
            return null;
        }
        for (Map.Entry<String, CuboidArea> entry : areas.entrySet()) {
            if (entry.getValue() == main) {
                return entry.getKey();
            }
        }
        // 官方约定：主区域的键固定是 "main"（对象同一性判断失败时的兜底）
        return areas.containsKey("main") ? "main" : null;
    }

    /** itemsToJson: 黑名单 / 忽略名单里的物品（id + 客户端翻译用的 key）。 */
    private JsonArray itemsToJson(ItemList list) {
        JsonArray array = new JsonArray();
        if (list == null) {
            return array;
        }
        Material[] materials = list.toArray();
        if (materials == null) {
            return array;
        }
        int count = 0;
        for (Material material : materials) {
            if (material == null || count++ >= MAX_LIST_ITEMS) {
                break;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", material.name());
            item.addProperty("key", material.isItem() ? material.getItemTranslationKey() : "");
            array.add(item);
        }
        return array;
    }

    /** commandsToJson: 领地的命令名单（blocked=true 为禁用，false 为单独放行）。 */
    private JsonArray commandsToJson(ClaimedResidence residence) {
        JsonArray array = new JsonArray();
        List<String> blocked = residence.getCmdBlackList();
        List<String> allowed = residence.getCmdWhiteList();
        int count = 0;
        if (blocked != null) {
            for (String cmd : blocked) {
                if (cmd == null || count++ >= MAX_LIST_ITEMS) {
                    break;
                }
                JsonObject item = new JsonObject();
                item.addProperty("cmd", cmd);
                item.addProperty("blocked", true);
                array.add(item);
            }
        }
        if (allowed != null) {
            for (String cmd : allowed) {
                if (cmd == null || count++ >= MAX_LIST_ITEMS) {
                    break;
                }
                JsonObject item = new JsonObject();
                item.addProperty("cmd", cmd);
                item.addProperty("blocked", false);
                array.add(item);
            }
        }
        return array;
    }

    /** chatToJson: 领地聊天频道的颜色 / 前缀 / 成员数（/res rc）。 */
    private JsonObject chatToJson(ClaimedResidence residence) {
        JsonObject chat = new JsonObject();
        chat.addProperty("prefix", residence.getChatPrefix() == null ? "" : residence.getChatPrefix());
        chat.addProperty("color", chatColorName(residence));
        int members = 0;
        try {
            // ChatChannel 只对外暴露 UUID 相关操作，没有「取成员名」的方法，这里只给数量
            com.bekvon.bukkit.residence.chat.ChatChannel channel = residence.getChatChannel();
            if (channel != null) {
                members = channel.memberCount();
            }
        } catch (Throwable throwable) {
            getLogger().warning("读取领地聊天成员数失败: " + throwable);
        }
        chat.addProperty("memberCount", members);
        return chat;
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