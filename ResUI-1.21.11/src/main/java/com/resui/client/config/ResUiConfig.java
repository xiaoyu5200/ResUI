package com.resui.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ResUiConfig: 客户端本地设置。
 * 存放在 config/resui.json，背景图放在 config/resui/ 下面。
 * 所有读取都做了容错：配置缺失/损坏时用默认值，不让界面因为配置问题打不开。
 */
public final class ResUiConfig {
    public static final ResUiConfig INSTANCE = new ResUiConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "resui.json";

    /** 设置目录（config/resui/），背景图就放这里。 */
    private final Path directory;
    private final Path file;

    // ---- 设置项 ----
    /** 用自定义背景图替代内置壁纸。 */
    private boolean customBackground;
    /** 背景图文件名（相对 config/resui/，只允许单层文件名）。 */
    private String backgroundFile = "background.png";
    /** 列表里显示「出售中 / 出租中」等市场状态。 */
    private boolean showMarketInList = true;
    /** 列表行里显示坐标范围。 */
    private boolean showCoordsInList;

    private ResUiConfig() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        this.directory = configDir.resolve("resui");
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    /** 设置目录（config/resui/）。 */
    public Path directory() {
        return directory;
    }

    /** 配置文件路径（config/resui.json）。 */
    public Path file() {
        return file;
    }

    public boolean customBackground() {
        return customBackground;
    }

    public void setCustomBackground(boolean value) {
        this.customBackground = value;
    }

    public String backgroundFile() {
        return backgroundFile == null ? "" : backgroundFile;
    }

    public void setBackgroundFile(String value) {
        this.backgroundFile = value == null ? "" : value.trim();
    }

    public boolean showMarketInList() {
        return showMarketInList;
    }

    public void setShowMarketInList(boolean value) {
        this.showMarketInList = value;
    }

    public boolean showCoordsInList() {
        return showCoordsInList;
    }

    public void setShowCoordsInList(boolean value) {
        this.showCoordsInList = value;
    }

    /**
     * resolveBackground: 把设置里的文件名解析成实际路径。
     * 只允许 config/resui/ 下的单层文件名——用户填 "a/b.png" 或 "../x.png" 会被拒绝，
     * 避免任意路径读取。
     */
    public Path resolveBackground() {
        String name = backgroundFile();
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        return directory.resolve(name);
    }

    /** load: 读配置；文件不存在时写一份默认配置，方便玩家直接改。 */
    public void load() {
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
        } catch (IOException ignored) {
            // 目录建不出来也不影响使用，保存时还会再试
        }
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            customBackground = bool(root, "customBackground", false);
            backgroundFile = string(root, "backgroundFile", "background.png");
            showMarketInList = bool(root, "showMarketInList", true);
            showCoordsInList = bool(root, "showCoordsInList", false);
        } catch (Throwable throwable) {
            // 配置损坏：保持默认值，不抛异常
            customBackground = false;
            backgroundFile = "background.png";
        }
    }

    /** save: 写回配置（先写临时文件再移动，避免写坏原文件）。 */
    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("customBackground", customBackground);
        root.addProperty("backgroundFile", backgroundFile);
        root.addProperty("showMarketInList", showMarketInList);
        root.addProperty("showCoordsInList", showCoordsInList);
        try {
            Files.createDirectories(directory.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            // 写失败不影响本次会话内的设置生效
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }
}
