package com.resui.client.bridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ResidenceRefreshPayload: 客户端 → 服务端的刷新请求（空包）。
 * 走自定义通道而不是转发命令字符串，避免和任何命令树冲突。
 */
public record ResidenceRefreshPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ResidenceRefreshPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("resui", "refresh"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidenceRefreshPayload> CODEC =
            CustomPacketPayload.codec((payload, buffer) -> {
            }, buffer -> new ResidenceRefreshPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}