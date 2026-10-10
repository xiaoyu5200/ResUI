package com.resui.client.bridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ResidenceActionPayload: 客户端 → 服务端的领地操作请求（JSON）。
 * 交给桥接插件执行并回传结果，避免依赖聊天栏命令、也能拿到成功/失败。
 */
public record ResidenceActionPayload(String json) implements CustomPacketPayload {
    private static final int MAX_LENGTH = 32767;

    public static final CustomPacketPayload.Type<ResidenceActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("resui", "action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidenceActionPayload> CODEC =
            CustomPacketPayload.codec(ResidenceActionPayload::write, ResidenceActionPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(json, MAX_LENGTH);
    }

    private static ResidenceActionPayload read(RegistryFriendlyByteBuf buffer) {
        return new ResidenceActionPayload(buffer.readUtf(MAX_LENGTH));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
