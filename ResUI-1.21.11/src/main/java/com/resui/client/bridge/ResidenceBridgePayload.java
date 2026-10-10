package com.resui.client.bridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * ResidenceBridgePayload: 服务端插件通过 resui:main 通道推送的领地快照。
 * 线格式与 Paper 侧一致：VarInt 长度 + UTF-8 JSON（FriendlyByteBuf.readUtf 可直接读取）。
 */
public record ResidenceBridgePayload(String json) implements CustomPacketPayload {
    private static final int MAX_LENGTH = 1_048_576;

    public static final CustomPacketPayload.Type<ResidenceBridgePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("resui", "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResidenceBridgePayload> CODEC =
            CustomPacketPayload.codec(ResidenceBridgePayload::write, ResidenceBridgePayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(json, MAX_LENGTH);
    }

    private static ResidenceBridgePayload read(RegistryFriendlyByteBuf buffer) {
        return new ResidenceBridgePayload(buffer.readUtf(MAX_LENGTH));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}