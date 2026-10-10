package com.resui.client.ui;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * PlayerHeads: 取玩家皮肤 + 画头像。
 *
 * 画头像直接用**原版的 {@link PlayerFaceRenderer}**——UV、帽子层、方块/叠层样式都由原版处理，
 * 不需要自己算 blit 参数（自己算过一次，参数错了会平铺、错位，且客户端这边无法自测）。
 *
 * 皮肤来源（全部同步，不做网络请求）：
 * - 本地玩家：Minecraft.player → DefaultPlayerSkin.get(profile)（原版就是这么给本地玩家兜底的）
 * - 其他玩家：PlayerInfo.getSkin()（skin manager 在列表同步时已填好）
 * 取不到就退回默认皮肤，不会因为皮肤没加载出来而画不出来。
 */
@Environment(EnvType.CLIENT)
public final class PlayerHeads {
    private PlayerHeads() {
    }

    /** localSkin: 本地玩家的皮肤，拿不到就用默认皮肤。 */
    public static PlayerSkin localSkin() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.player != null) {
                GameProfile profile = minecraft.player.getGameProfile();
                if (profile != null) {
                    return DefaultPlayerSkin.get(profile);
                }
            }
            if (minecraft.getGameProfile() != null) {
                return DefaultPlayerSkin.get(minecraft.getGameProfile());
            }
        } catch (Throwable ignored) {
            // 落到下面的默认皮肤
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    /** skinOf: 在线玩家的皮肤（tab 列表里带的就是这个）。 */
    public static PlayerSkin skinOf(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.getConnection() != null) {
                for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                    String playerName = info.getProfile().name();
                    if (playerName != null && playerName.equalsIgnoreCase(name)) {
                        PlayerSkin skin = info.getSkin();
                        if (skin != null) {
                            return skin;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 落到默认皮肤
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    /**
     * drawHead: 画一个 size×size 的头像。
     * 内部走原版 PlayerFaceRenderer，所以显示效果与 tab 列表里的头像一致。
     */
    public static void drawHead(GuiGraphics graphics, PlayerSkin skin, int x, int y, int size) {
        try {
            PlayerFaceRenderer.draw(graphics, skin == null ? DefaultPlayerSkin.getDefaultSkin() : skin,
                    x, y, size);
        } catch (Throwable ignored) {
            // 画不出来就算了，不影响其它界面
        }
    }
}
