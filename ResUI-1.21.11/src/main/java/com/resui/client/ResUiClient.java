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
    private static final KeyMapping.Category KEY_CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath("resui", "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
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
        });
    }

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