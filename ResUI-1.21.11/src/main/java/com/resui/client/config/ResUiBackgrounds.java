package com.resui.client.config;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ResUiBackgrounds: 自定义背景图（PNG 静图）的加载与缓存。
 *
 * 走的是原版 DynamicTexture 这条路（不是 Fabric API 的图片加载，那样要注册资源包）：
 *   NativeImage.read(文件流) → new DynamicTexture(...) → TextureManager.register(Identifier, texture)
 * 之后就能像内置贴图一样用 RenderPipelines.GUI_TEXTURED 画出来。
 *
 * 失败一律退回内置背景，并把原因写在设置页的「状态：」那一行，玩家能自己看出是哪一步不对。
 */
public final class ResUiBackgrounds {
    public static final ResUiBackgrounds INSTANCE = new ResUiBackgrounds();

    /** 内置背景（assets/resui/textures/gui/background.png）。 */
    public static final Identifier DEFAULT =
            Identifier.fromNamespaceAndPath("resui", "textures/gui/background.png");
    /** 内置背景的像素尺寸（贴图文件就是 1280×720）。 */
    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;

    /** 纹理尺寸上限（超过就等比缩小，避免显存与缩放问题）。 */
    private static final int MAX_SIZE = 4096;
    /** 文件大小上限（8MB，够 4K PNG 了）。 */
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    private Identifier current = DEFAULT;
    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private boolean custom;
    /** 最近一次加载的状态说明（设置页直接显示它）。 */
    private String status = "当前使用内置背景";
    private String loadedFrom = "";
    private FileTime loadedStamp;
    private long loadedSize = -1L;
    /** 已注册的自定义纹理，换图时要 release 掉，避免纹理泄漏。 */
    private Identifier registered;
    /** 老版本遗留文件的清理是否已经做过（只做一次）。 */
    private boolean cleanupDone;

    private ResUiBackgrounds() {
    }

    /**
     * scanImages: 扫描设置目录里的 PNG 图片（按文件名排序），供「选背景」列表使用。
     */
    public List<String> scanImages() {
        Path directory = ResUiConfig.INSTANCE.directory();
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".png"))
                    .forEach(names::add);
        } catch (Throwable ignored) {
            // 扫描失败就当没有候选
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** cycleImage: 在候选之间切换（delta = +1 / -1），切换后立刻生效。 */
    public String cycleImage(int delta) {
        List<String> images = scanImages();
        if (images.isEmpty()) {
            useDefault("设置目录里没有 PNG 图片：" + ResUiConfig.INSTANCE.directory());
            return "";
        }
        ResUiConfig config = ResUiConfig.INSTANCE;
        int index = images.indexOf(config.backgroundFile());
        int next = index < 0 ? 0 : Math.floorMod(index + delta, images.size());
        String name = images.get(next);
        selectBackground(name);
        return name;
    }

    /** selectBackground: 选中一张背景图并立刻生效。 */
    public void selectBackground(String name) {
        ResUiConfig config = ResUiConfig.INSTANCE;
        config.setBackgroundFile(name);
        config.setCustomBackground(true);
        config.save();
        // 绕过「文件没变就跳过」的判断，立刻生效
        loadedFrom = "";
        loadedSize = -1L;
        loadedStamp = null;
        apply();
    }

    /**
     * cleanLegacyFiles: 删掉老版本在这个目录里生成的「说明.txt」。
     * 说明文字现在直接写在设置页上，所以那个文件没用了；
     * **示例 background.png 保留**——它本来就是给玩家替换用的默认背景。
     */
    private void cleanLegacyFiles() {
        try {
            Files.deleteIfExists(ResUiConfig.INSTANCE.directory().resolve("说明.txt"));
        } catch (Throwable ignored) {
            // 删不掉就算了（比如文件被占用）
        }
    }

    /** ensureReady: 首次进游戏时建目录并做一次老文件清理。 */
    public void ensureReady() {
        if (cleanupDone) {
            return;
        }
        cleanupDone = true;
        try {
            Files.createDirectories(ResUiConfig.INSTANCE.directory());
        } catch (Throwable ignored) {
            return;
        }
        cleanLegacyFiles();
    }

    public Identifier background() {
        return current;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean custom() {
        return custom;
    }

    public String status() {
        return status;
    }

    public String loadedFrom() {
        return loadedFrom;
    }

    /** apply: 按配置决定用内置还是自定义背景图。 */
    public void apply() {
        ResUiConfig config = ResUiConfig.INSTANCE;
        if (!config.customBackground()) {
            useDefault("自定义背景已关闭");
            return;
        }
        Path path = config.resolveBackground();
        if (path == null) {
            useDefault("文件名不合法（只允许 config/resui/ 下的单层文件名，如 bg.png）");
            return;
        }
        if (!Files.isRegularFile(path)) {
            useDefault("文件不存在：" + path);
            return;
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                useDefault("图片太大（超过 8MB）：" + path.getFileName());
                return;
            }
        } catch (Throwable ignored) {
            // 取不到大小就继续尝试读取
        }
        FileTime stamp = null;
        long size = -1L;
        try {
            stamp = Files.getLastModifiedTime(path);
            size = Files.size(path);
        } catch (Throwable ignored) {
            // 拿不到就用内容判断
        }
        // 文件没变就不重复加载（换图后点「重新加载」会走下面的加载分支）
        if (custom && path.toString().equals(loadedFrom) && size == loadedSize
                && (stamp == null || stamp.equals(loadedStamp))) {
            return;
        }
        load(path, stamp, size);
    }

    /** load: 把 PNG 读成纹理并注册。 */
    private void load(Path path, FileTime stamp, long size) {
        NativeImage image = null;
        try (InputStream in = Files.newInputStream(path)) {
            image = NativeImage.read(in);
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            if (imageWidth <= 0 || imageHeight <= 0) {
                image.close();
                useDefault("图片尺寸无效：" + imageWidth + "×" + imageHeight);
                return;
            }
            // 过大就等比缩到上限内（NativeImage 没有 resize，自己按最近邻重采样）
            if (imageWidth > MAX_SIZE || imageHeight > MAX_SIZE) {
                int longest = Math.max(imageWidth, imageHeight);
                int scaledWidth = Math.max(1, imageWidth * MAX_SIZE / longest);
                int scaledHeight = Math.max(1, imageHeight * MAX_SIZE / longest);
                NativeImage scaled = downscale(image, scaledWidth, scaledHeight);
                if (scaled != null) {
                    image.close();
                    image = scaled;
                    imageWidth = scaledWidth;
                    imageHeight = scaledHeight;
                }
            }
            // 换图前把上一张自定义纹理放掉
            clearCustomTexture();

            Identifier id = Identifier.fromNamespaceAndPath("resui", "dynamic/background");
            DynamicTexture texture = new DynamicTexture(() -> "resui background", image);
            texture.upload();
            Minecraft.getInstance().getTextureManager().register(id, texture);

            this.current = id;
            this.registered = id;
            this.width = imageWidth;
            this.height = imageHeight;
            this.custom = true;
            this.status = "自定义背景已生效";
            this.loadedFrom = path.toString();
            this.loadedStamp = stamp;
            this.loadedSize = size;
        } catch (Throwable throwable) {
            if (image != null) {
                image.close();
            }
            String message = throwable.getClass().getSimpleName();
            if (throwable.getMessage() != null) {
                message = message + ": " + throwable.getMessage();
            }
            useDefault("读取失败（需要是 PNG 图片）：" + message);
        }
    }

    /**
     * downscale: 最近邻缩小（只在图片超过 MAX_SIZE 时调用）。
     * NativeImage 没有现成的缩放方法，这里按目标尺寸逐像素取样；失败返回 null 表示保持原图。
     */
    private static NativeImage downscale(NativeImage source, int width, int height) {
        try {
            NativeImage target = new NativeImage(width, height, false);
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            for (int y = 0; y < height; y++) {
                int sourceY = (int) ((long) y * sourceHeight / height);
                for (int x = 0; x < width; x++) {
                    int sourceX = (int) ((long) x * sourceWidth / width);
                    target.setPixelABGR(x, y, source.getPixel(sourceX, sourceY));
                }
            }
            return target;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** useDefault: 回到内置背景，并记录原因。 */
    private void useDefault(String reason) {
        clearCustomTexture();
        current = DEFAULT;
        width = DEFAULT_WIDTH;
        height = DEFAULT_HEIGHT;
        custom = false;
        loadedFrom = "";
        loadedStamp = null;
        loadedSize = -1L;
        status = reason;
    }

    /** clearCustomTexture: 释放之前注册的自定义纹理。 */
    private void clearCustomTexture() {
        if (registered == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(registered);
        } catch (Throwable ignored) {
            // 释放失败无所谓，下次注册会覆盖
        }
        registered = null;
    }
}
