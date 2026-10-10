package com.resui.client.config;

import com.resui.client.ResUiClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Consumer;

/**
 * ResUiSettingsPanel: 设置页的绘制逻辑（领地列表页与管理面板共用）。
 *
 * 页面本身不持有界面状态：滚动位置、输入框内容与点击注册都由调用方提供，
 * 调用方只需要实现 {@link Interaction} 与 {@link Input} 两个口子。
 */
@Environment(EnvType.CLIENT)
public final class ResUiSettingsPanel {
    public static final String PAGE_TITLE = "设置";

    // 颜色（与两个界面保持同一套）
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;
    private static final int COLOR_OK = 0xFF6EE06E;
    private static final int COLOR_ROW = 0x55202020;
    private static final int COLOR_ROW_ALT = 0x33202020;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_BTN = 0xFF565656;
    private static final int COLOR_BTN_HOVER = 0xFF6C6C6C;
    private static final int COLOR_ON = 0xFF2E7D32;
    private static final int COLOR_ON_HOVER = 0xFF3FA34D;
    private static final int COLOR_OFF = 0xFF6B2F2F;
    private static final int COLOR_OFF_HOVER = 0xFF8A4A4A;

    private static final int TOGGLE_W = 48;
    private static final int TOGGLE_H = 18;
    private static final int FIELD_W = 220;
    private static final int FIELD_H = 16;
    private static final int BTN_H = 18;

    /** Interaction: 点击注册（由调用方把回调塞进自己的可点列表）。 */
    public interface Interaction {
        void clickable(int x1, int y1, int x2, int y2, Runnable action);

        boolean isInside(double mouseX, double mouseY, int x1, int y1, int x2, int y2);
    }

    /** Input: 输入框状态由调用方保管（两个界面的实现不同）。 */
    public interface Input {
        String value(String key);

        void setValue(String key, String value);

        String focused();

        void focus(String key);

        /** ensureDefaults: 绘制前把空输入框填上默认值。 */
        default void ensureDefaults() {
        }
    }

    private ResUiSettingsPanel() {
    }

    /**
     * draw: 画设置页，返回内容底部 y。
     *
     * @param x       内容左边界
     * @param right   内容右边界
     * @param contentTop    可视区上沿（开关的可点性判定用）
     * @param contentBottom 可视区下沿
     */
    public static int draw(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                           int x, int y, int right, int contentTop, int contentBottom,
                           Interaction interaction, Input input) {
        // 输入框的默认值在绘制前补好（各界面自己知道默认该填什么）
        input.ensureDefaults();        ResUiConfig config = ResUiConfig.INSTANCE;
        ResUiBackgrounds backgrounds = ResUiBackgrounds.INSTANCE;
        y = title(screen, graphics, x, y, "设置");
        y = hint(screen, graphics, x, y, "这里的设置只影响你自己的客户端，保存在 config/resui.json");
        y += 4;

        // ---- 自定义背景图（按钮式：打开目录 → 扫描 → 选图，选中即生效）----
        y = section(screen, graphics, x, y, "自定义背景图");
        y = toggle(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom, x, y,
                "使用自定义背景", config.customBackground(), value -> {
                    config.setCustomBackground(value);
                    config.save();
                    ResUiClient.applyConfig();
                });
        y += 4;
        int buttonX = x;
        buttonX = button(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom,
                buttonX, y, "打开设置目录", ResUiClient::openConfigDir);
        buttonX = button(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom,
                buttonX, y, "扫描图片", ResUiClient::rescanBackgrounds);
        buttonX = button(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom,
                buttonX, y, "上一张", () -> ResUiClient.cycleBackground(-1));
        button(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom,
                buttonX, y, "下一张", () -> ResUiClient.cycleBackground(1));
        y += BTN_H + 6;
        // 候选图直接列成按钮（点哪张切哪张，选中即生效）
        List<String> images = ResUiBackgrounds.INSTANCE.scanImages();
        if (images.isEmpty()) {
            y = hint(screen, graphics, x, y, "设置目录里还没有 PNG 图片——点「打开设置目录」放进去，回来点「扫描图片」");
        } else {
            String currentName = config.backgroundFile();
            graphics.drawString(screen.getFont(), "点下面任意一张即可切换（✓ = 当前使用）",
                    x, y, COLOR_MUTED, true);
            y += 12;
            for (String name : images) {
                boolean selected = name.equalsIgnoreCase(currentName);
                String label = (selected ? "✓ " : "· ") + name;
                int width = 16 + screen.getFont().width(label);
                boolean hover = interaction.isInside(mouseX, mouseY, x, y, x + width, y + BTN_H);
                graphics.fill(x, y, x + width, y + BTN_H,
                        selected || hover ? COLOR_BTN_HOVER : COLOR_BTN);
                border(graphics, x, y, x + width, y + BTN_H);
                graphics.drawString(screen.getFont(), label, x + 8, y + 5,
                        selected ? COLOR_TEXT : COLOR_MUTED, true);
                if (y + BTN_H > contentTop && y < contentBottom) {
                    interaction.clickable(x, y, x + width, y + BTN_H,
                            () -> ResUiClient.selectBackground(name));
                }
                y += BTN_H + 4;
            }
        }
        y += 2;
        button(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom,
                x, y, "恢复内置背景", () -> {
                    config.setCustomBackground(false);
                    config.save();
                    ResUiClient.applyConfig();
                });
        y += BTN_H + 8;
        String status = backgrounds.status();
        graphics.drawString(screen.getFont(), "状态：" + status, x, y,
                backgrounds.custom() ? COLOR_OK : COLOR_MUTED, true);
        y += 12;
        if (backgrounds.custom() && !backgrounds.loadedFrom().isBlank()) {
            y = hint(screen, graphics, x, y, "已加载 " + backgrounds.width() + "×" + backgrounds.height()
                    + "（比例与窗口不同时等比铺满并居中裁切，不会拉变形）");
        }
        y += 8;

        // ---- 列表显示 ----
        y = section(screen, graphics, x, y, "列表显示");
        y = toggle(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom, x, y,
                "显示市场状态（出售 / 出租）", config.showMarketInList(), value -> {
                    config.setShowMarketInList(value);
                    config.save();
                });
        y += 4;
        y = toggle(screen, graphics, mouseX, mouseY, interaction, contentTop, contentBottom, x, y,
                "在列表里显示坐标范围", config.showCoordsInList(), value -> {
                    config.setShowCoordsInList(value);
                    config.save();
                });
        y += 10;
        y = hint(screen, graphics, x, y, "配置文件：" + config.file().toAbsolutePath());
        return y + 6;
    }

    /** smallField: 带标题的窄输入框（播放参数用）。 */
    private static int smallField(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                                  Interaction interaction, Input input, int x, int y,
                                  String key, String value, String hint, int width) {
        boolean focused = key.equals(input.focused());
        graphics.fill(x, y, x + width, y + 16, focused ? 0xFF101010 : 0xFF1A1A1A);
        border(graphics, x, y, x + width, y + 16);
        graphics.drawString(screen.getFont(), value == null || value.isEmpty() ? hint : value, x + 4, y + 5,
                value == null || value.isEmpty() ? COLOR_MUTED : COLOR_TEXT, true);
        if (focused) {
            int caretX = x + 4 + screen.getFont().width(value);
            graphics.fill(caretX + 1, y + 3, caretX + 2, y + 13, 0xFFDDDDDD);
        }
        interaction.clickable(x, y, x + width, y + 16, () -> input.focus(key));
        return x + width + 4;
    }

    /** parseInt: 宽容解析（非法就保持原值）。 */
    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- 自绘控件

    private static int title(Screen screen, GuiGraphics graphics, int x, int y, String text) {
        graphics.drawString(screen.getFont(), text, x, y, COLOR_TEXT, true);
        return y + 16;
    }

    private static int hint(Screen screen, GuiGraphics graphics, int x, int y, String text) {
        graphics.drawString(screen.getFont(), text, x, y, COLOR_MUTED, true);
        return y + 12;
    }

    private static int section(Screen screen, GuiGraphics graphics, int x, int y, String text) {
        graphics.drawString(screen.getFont(), text, x, y, COLOR_TEXT, true);
        int lineY = y + 11;
        graphics.fill(x, lineY, x + 200, lineY + 1, COLOR_BORDER);
        return y + 18;
    }

    private static int button(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                              Interaction interaction, int contentTop, int contentBottom,
                              int x, int y, String label, Runnable action) {
        int width = 14 + screen.getFont().width(label);
        boolean hover = interaction.isInside(mouseX, mouseY, x, y, x + width, y + BTN_H);
        graphics.fill(x, y, x + width, y + BTN_H, hover ? COLOR_BTN_HOVER : COLOR_BTN);
        border(graphics, x, y, x + width, y + BTN_H);
        graphics.drawString(screen.getFont(), label, x + (width - screen.getFont().width(label)) / 2,
                y + (BTN_H - 8) / 2, COLOR_TEXT, true);
        // 手写视口判定：滚动区外的按钮不该可点
        if (y + BTN_H > contentTop && y < contentBottom) {
            interaction.clickable(x, y, x + width, y + BTN_H, action);
        }
        return x + width + 6;
    }

    private static int toggle(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                              Interaction interaction, int contentTop, int contentBottom,
                              int x, int y, String label, boolean value, Consumer<Boolean> onChange) {
        graphics.drawString(screen.getFont(), label, x, y + (TOGGLE_H - 8) / 2, COLOR_TEXT, true);
        int toggleX = x + Math.max(170, screen.getFont().width(label) + 16);
        boolean hover = interaction.isInside(mouseX, mouseY, toggleX, y, toggleX + TOGGLE_W, y + TOGGLE_H);
        int background;
        if (value) {
            background = hover ? COLOR_ON_HOVER : COLOR_ON;
        } else {
            background = hover ? COLOR_OFF_HOVER : COLOR_OFF;
        }
        graphics.fill(toggleX, y, toggleX + TOGGLE_W, y + TOGGLE_H, background);
        border(graphics, toggleX, y, toggleX + TOGGLE_W, y + TOGGLE_H);
        String text = value ? "开启" : "关闭";
        graphics.drawString(screen.getFont(), text,
                toggleX + (TOGGLE_W - screen.getFont().width(text)) / 2, y + (TOGGLE_H - 8) / 2, COLOR_TEXT, true);
        if (y + TOGGLE_H > contentTop && y < contentBottom) {
            interaction.clickable(toggleX, y, toggleX + TOGGLE_W, y + TOGGLE_H, () -> onChange.accept(!value));
        }
        return y + TOGGLE_H + 6;
    }

    private static void border(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y1 + 1, COLOR_BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, COLOR_BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, COLOR_BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, COLOR_BORDER);
    }

    /** wrap: 极简按宽度折行（需要多行说明时用）。 */
    public static List<String> wrap(Screen screen, String text, int maxWidth) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (screen.getFont().width(current.toString() + c) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
