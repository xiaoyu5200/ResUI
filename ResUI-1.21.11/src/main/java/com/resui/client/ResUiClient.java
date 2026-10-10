package com.resui.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.resui.client.bridge.ResidenceActionPayload;
import com.resui.client.bridge.ResidenceBridgePayload;
import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceRefreshPayload;
import com.google.gson.JsonObject;
import com.resui.client.residence.ResidenceListScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 * 注意：这里刻意不注册 /res —— 那会拦截服务端 Residence 的 /res 命令树（/res tp 等会报错）。
 * - /resui            刷新数据并打开领地列表
 * - /resui refresh    只刷新数据
 * - /resui tp <名称>  传送
 * - 快捷键（默认 R）   刷新并打开领地列表
 */
@Environment(EnvType.CLIENT)
public final class ResUiClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResUI");
    private static final KeyMapping.Category KEY_CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath("resui", "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        // 启动时把版本打进日志：排查「界面没变化」时，先确认游戏里加载的是哪一版
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("resui")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("未知");
        LOGGER.info("[ResUI] 客户端已加载，版本 {}（头像走原版 PlayerFaceRenderer）", version);

        // 读取本地设置（config/resui.json）并按它加载自定义背景图
        com.resui.client.config.ResUiBackgrounds.INSTANCE.ensureReady();
        com.resui.client.config.ResUiBackgrounds.INSTANCE.apply();

        PayloadTypeRegistry.playS2C().register(ResidenceBridgePayload.TYPE, ResidenceBridgePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ResidenceBridgePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ResidenceCache.INSTANCE.updateFromJson(payload.json())));

        PayloadTypeRegistry.playC2S().register(ResidenceRefreshPayload.TYPE, ResidenceRefreshPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ResidenceActionPayload.TYPE, ResidenceActionPayload.CODEC);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("resui")
                    .executes(context -> {
                        openList();
                        return 1;
                    })
                    .then(ClientCommandManager.literal("refresh").executes(context -> {
                        requestRefresh();
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("tp")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        teleport(StringArgumentType.getString(context, "name"));
                                        return 1;
                                    }))));
        });

        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.resui.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                openList();
            }
            // 进世界后提示一次自定义背景图的目录（只在多人游戏里提示，单人模式用不到这个 mod）
            if (!backgroundHintShown && client.player != null && client.getConnection() != null
                    && client.getSingleplayerServer() == null) {
                backgroundHintShown = true;
                printBackgroundHint(client);
            }
        });
    }

    /** backgroundHintShown: 自定义背景图目录提示只发一次。 */
    private static boolean backgroundHintShown;

    /** openList: 先请求一次最新快照，再打开列表（数据到了界面会自动刷新）。 */
    public static void openList() {
        Minecraft minecraft = Minecraft.getInstance();
        if (isSingleplayer(minecraft)) {
            notifyUnavailable(minecraft);
            return;
        }
        requestRefresh();
        minecraft.execute(() -> minecraft.setScreen(new ResidenceListScreen()));
    }

    /** isSingleplayer: 单人（含本地开房间）世界里没有服务端桥接插件，本 mod 不可用。 */
    private static boolean isSingleplayer(Minecraft minecraft) {
        return minecraft.getSingleplayerServer() != null;
    }

    private static void notifyUnavailable(Minecraft minecraft) {
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("§c[ResUI] 单人模式下不可用（需要服务端安装 ResUI-Bridge 插件）"), false);
        }
    }

    /** requestRefresh: 优先走自定义通道请求；服务器没装插件时回退到命令。 */
    public static void requestRefresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null || isSingleplayer(minecraft)) {
            return;
        }
        if (ClientPlayNetworking.canSend(ResidenceRefreshPayload.TYPE)) {
            ClientPlayNetworking.send(new ResidenceRefreshPayload());
        } else {
            minecraft.getConnection().sendCommand("resbridge");
        }
    }

    /** teleport: 转发服务端 /res tp 命令（只读操作，不需要回执）。 */
    public static void teleport(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("res tp " + name);
        }
    }

    /** setTeleportable: 设置领地 tp 权限（公开/私有）。 */
    public static void setTeleportable(String name, boolean value) {
        sendAction("set_flag", name, action -> {
            action.addProperty("flag", "tp");
            action.addProperty("value", String.valueOf(value));
        });
    }

    /** setFlag: 设置领地的单个权限。 */
    public static void setFlag(String name, String flag, boolean value) {
        sendAction("set_flag", name, action -> {
            action.addProperty("flag", flag);
            action.addProperty("value", String.valueOf(value));
        });
    }

    /** addMember: 把玩家加为领地成员。 */
    public static void addMember(String name, String player) {
        sendAction("add_member", name, action -> action.addProperty("player", player));
    }

    /** removeMember: 移除领地成员的权限。 */
    public static void removeMember(String name, String player) {
        sendAction("remove_member", name, action -> action.addProperty("player", player));
    }

    /** setMemberFlag: 设置某成员在领地中的单项权限；state 取 true / false / remove。 */
    public static void setMemberFlag(String name, String player, String flag, String state) {
        sendAction("set_member_flag", name, action -> {
            action.addProperty("player", player);
            action.addProperty("flag", flag);
            action.addProperty("value", state);
        });
    }

    /** setTeleportPoint: 把玩家当前位置设为该领地的传送点。 */
    public static void setTeleportPoint(String name) {
        sendAction("tpset", name, action -> {
        });
    }

    /** refreshMe: 让服务端重算「当前所在领地 / 我的上限」（等价 /res current 与 /res limits）。 */
    public static void refreshMe() {
        sendAction("limits", "", action -> {
        });
    }

    /** setMessage: 设置（或清除，text 为空时）领地的进入 / 离开提示消息。 */
    public static void setMessage(String name, String kind, String text) {
        sendAction("set_message", name, action -> {
            action.addProperty("kind", kind);
            action.addProperty("text", text == null ? "" : text);
        });
    }

    /** setListItem: 往黑名单 / 忽略名单加（remove=false）或移除（remove=true）一个物品。 */
    public static void setListItem(String name, String list, String material, boolean remove) {
        sendAction(remove ? "list_remove" : "list_add", name, action -> {
            action.addProperty("list", list);
            action.addProperty("material", material);
        });
    }

    /** setCommandRule: 领地内命令的放行 / 禁用 / 从名单移除。 */
    public static void setCommandRule(String name, String cmd, String mode) {
        String action = switch (mode) {
            case "allow" -> "command_allow";
            case "remove" -> "command_remove";
            default -> "command_block";
        };
        sendAction(action, name, json -> json.addProperty("cmd", cmd));
    }

    /** sendCommand: 转发一条服务端命令（只读 / 原版功能，不需要回执）。 */
    public static void sendCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
    }

    // ---------------------------------------------------------------- 本地设置（纯客户端）

    /** applyConfig: 保存设置后重新加载背景图。 */
    public static void applyConfig() {
        com.resui.client.config.ResUiBackgrounds.INSTANCE.apply();
    }

    /** rescanBackgrounds: 重新扫描设置目录里的图片（放好图之后点一下）。 */
    public static void rescanBackgrounds() {
        java.util.List<String> images = com.resui.client.config.ResUiBackgrounds.INSTANCE.scanImages();
        com.resui.client.config.ResUiBackgrounds.INSTANCE.apply();
        if (images.isEmpty()) {
            ResidenceCache.INSTANCE.setFeedback(false,
                    "设置目录里没有 PNG 图片：" + com.resui.client.config.ResUiConfig.INSTANCE.directory());
        } else {
            ResidenceCache.INSTANCE.setFeedback(true, "扫到 " + images.size() + " 张图片，点列表切换");
        }
    }

    /** cycleBackground: 在候选图片之间前后切换（选中即生效）。 */
    public static void cycleBackground(int delta) {
        String name = com.resui.client.config.ResUiBackgrounds.INSTANCE.cycleImage(delta);
        if (!name.isEmpty()) {
            ResidenceCache.INSTANCE.setFeedback(true, "已切换到：" + name);
        }
    }

    /** selectBackground: 直接选中某张背景图。 */
    public static void selectBackground(String name) {
        com.resui.client.config.ResUiBackgrounds.INSTANCE.selectBackground(name);
        ResidenceCache.INSTANCE.setFeedback(true, "已切换到：" + name);
    }

    /** printBackgroundHint: 进游戏时在聊天栏说明自定义背景图放在哪（客户端 mod 没有配置文件之外的说明渠道）。 */
    private static void printBackgroundHint(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        java.nio.file.Path directory = com.resui.client.config.ResUiConfig.INSTANCE.directory();
        minecraft.player.displayClientMessage(
                Component.literal("§b[ResUI] §7背景图目录：§f" + directory.toAbsolutePath()), false);
        minecraft.player.displayClientMessage(
                Component.literal("§7把 PNG 丢进去，点右上角 §f头像 → 设置 §7，按钮里直接选图"), false);
    }

    /** copyToClipboard: 把文本放进系统剪贴板（设置页用它给出图片该放哪）。 */
    public static void copyToClipboard(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            minecraft.keyboardHandler.setClipboard(text);
            ResidenceCache.INSTANCE.setFeedback(true, "已复制到剪贴板");
        } catch (Throwable throwable) {
            ResidenceCache.INSTANCE.setFeedback(false, "复制失败：" + throwable.getClass().getSimpleName());
        }
    }

    /** openConfigDir: 用系统文件管理器打开 config/resui/，方便玩家把图片丢进去。 */
    public static void openConfigDir() {
        java.nio.file.Path directory = com.resui.client.config.ResUiConfig.INSTANCE.directory();
        try {
            java.nio.file.Files.createDirectories(directory);
        } catch (Throwable ignored) {
            // 目录已存在或建不出来，继续尝试打开
        }
        boolean opened = false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("explorer", directory.toAbsolutePath().toString());
            } else if (os.contains("mac")) {
                builder = new ProcessBuilder("open", directory.toAbsolutePath().toString());
            } else {
                builder = new ProcessBuilder("xdg-open", directory.toAbsolutePath().toString());
            }
            builder.start();
            opened = true;
        } catch (Throwable ignored) {
            // 打不开就走下面的复制路径提示
        }
        if (opened) {
            ResidenceCache.INSTANCE.setFeedback(true, "已打开设置目录");
        } else {
            copyToClipboard(directory.toAbsolutePath().toString());
        }
    }

    // ---------------------------------------------------------------- 阶段 2：区域与子领地

    /** areaAction: add / replace / remove / rename / setmain / tp，参数按需带 id。 */
    public static void areaAction(String name, String mode, String id, String newId) {
        sendAction("area_" + mode, name, action -> {
            if (id != null && !id.isEmpty()) {
                action.addProperty("id", id);
            }
            if (newId != null && !newId.isEmpty()) {
                action.addProperty("newId", newId);
            }
        });
    }

    /** teleportToArea: 传送到指定区域的中心。 */
    public static void teleportToArea(String name, String areaId) {
        sendAction("tp_area", name, action -> action.addProperty("id", areaId));
    }

    /** subzoneAction: create（用当前选区）/ remove。 */
    public static void subzoneAction(String name, String mode, String subName) {
        sendAction("subzone_" + mode, name, action -> {
            if (subName != null && !subName.isEmpty()) {
                action.addProperty("sub", subName);
            }
        });
    }

    // ---------------------------------------------------------------- 阶段 3：危险操作

    /** renameResidence: 重命名领地或子领地。 */
    public static void renameResidence(String name, String newName) {
        sendAction("rename", name, action -> action.addProperty("newName", newName));
    }

    /** resizeResidence: 对某个区域整体放大（expand）或缩小（contract）。 */
    public static void resizeResidence(String name, String mode, String areaId, int amount) {
        sendAction(mode, name, action -> {
            action.addProperty("amount", String.valueOf(amount));
            if (areaId != null && !areaId.isEmpty()) {
                action.addProperty("id", areaId);
            }
        });
    }

    /**
     * giveResidence / removeResidence: 危险操作，客户端已弹红字二次确认，
     * confirm=true 是服务端执行它们的前提。
     */
    public static void giveResidence(String name, String player) {
        sendAction("give", name, action -> {
            action.addProperty("player", player);
            action.addProperty("confirm", "true");
        });
    }

    public static void removeResidence(String name) {
        sendAction("remove", name, action -> action.addProperty("confirm", "true"));
    }

    // ---------------------------------------------------------------- 阶段 4：市场与出租

    /** sellResidence: 挂牌出售；price 为 0 时表示调用方已经校验过，这里只做透传。 */
    public static void sellResidence(String name, int price) {
        sendAction("market_sell", name, action -> action.addProperty("price", String.valueOf(price)));
    }

    public static void unsellResidence(String name) {
        sendAction("market_unsell", name, action -> {
        });
    }

    public static void buyResidence(String name) {
        sendAction("market_buy", name, action -> action.addProperty("confirm", "true"));
    }

    /** rentableResidence: 挂牌出租（租金 / 天数 / 是否允许续租与自动续租）。 */
    public static void rentableResidence(String name, int cost, int days, boolean renew, boolean autoPay) {
        sendAction("market_rentable", name, action -> {
            action.addProperty("cost", String.valueOf(cost));
            action.addProperty("days", String.valueOf(days));
            action.addProperty("renew", String.valueOf(renew));
            action.addProperty("autopay", String.valueOf(autoPay));
        });
    }

    public static void unrentableResidence(String name) {
        sendAction("market_unrentable", name, action -> {
        });
    }

    public static void rentResidence(String name, boolean autoPay) {
        sendAction("market_rent", name, action -> {
            action.addProperty("autopay", String.valueOf(autoPay));
            action.addProperty("confirm", "true");
        });
    }

    public static void unrentResidence(String name) {
        sendAction("market_unrent", name, action -> {
        });
    }

    public static void setRentAutoPay(String name, boolean value) {
        sendAction("market_autopay", name, action -> action.addProperty("value", String.valueOf(value)));
    }

    public static void payRent(String name) {
        sendAction("market_payrent", name, action -> {
        });
    }

    public static void renewLease(String name) {
        sendAction("lease_renew", name, action -> {
        });
    }

    // ---------------------------------------------------------------- 阶段 5：领地银行与聊天

    /** bankAction: deposit / withdraw，amount 由调用方先做数值校验。 */
    public static void bankAction(String name, String mode, int amount) {
        sendAction("bank_" + mode, name, action -> action.addProperty("amount", String.valueOf(amount)));
    }

    public static void chatJoin(String name) {
        sendAction("chat_join", name, action -> {
        });
    }

    public static void chatLeave(String name) {
        sendAction("chat_leave", name, action -> {
        });
    }

    public static void setChatSettings(String name, String prefix, String color) {
        sendAction("chat_set", name, action -> {
            action.addProperty("prefix", prefix == null ? "" : prefix);
            action.addProperty("color", color == null ? "" : color);
        });
    }

    public static void kickFromResidence(String name, String player) {
        sendAction("chat_kick", name, action -> action.addProperty("player", player));
    }

    /** sendAction: 通过 resui:action 通道把操作交给服务端插件执行，结果随下次快照回传。 */
    private static void sendAction(String action, String residence, java.util.function.Consumer<JsonObject> filler) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ResidenceActionPayload.TYPE)) {
            ResidenceCache.INSTANCE.setFeedback(false, "服务端未安装 ResUI-Bridge 插件");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        json.addProperty("residence", residence);
        filler.accept(json);
        ClientPlayNetworking.send(new ResidenceActionPayload(json.toString()));
    }
}