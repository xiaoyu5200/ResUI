package com.resui.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

/**
 * ResUiOverlayPanel: 居中的覆盖层面板（设置、领地商城都用它）。
 *
 * 负责：压暗背景、面板底与标题栏、关闭按钮、内容裁剪与滚动、把内容里注册的点击接进界面的可点列表。
 * 内容由调用方通过 {@link Content} 提供，只需要「画」并返回内容高度。
 */
@Environment(EnvType.CLIENT)
public final class ResUiOverlayPanel {
    private static final int COLOR_TITLE_BAR = 0xFF303030;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_BTN = 0xFF565656;
    private static final int COLOR_BTN_HOVER = 0xFF6C6C6C;
    private static final int PAD = 8;
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int CLOSE_SIZE = 18;

    /** Clickable: 面板里的一个可点区域（调用方负责接到自己的点击列表）。 */
    public interface Clickable {
        void add(int x1, int y1, int x2, int y2, Runnable action);
    }

    /** Inside: 鼠标命中判定（用调用方自己的实现，保证两个界面一致）。 */
    public interface Inside {
        boolean test(double mouseX, double mouseY, int x1, int y1, int x2, int y2);
    }

    /** Content: 画面板内容，返回内容高度（用于算滚动范围）。 */
    public interface Content {
        int draw(GuiGraphics graphics, int mouseX, int mouseY,
                 int contentX, int contentTop, int contentRight, int contentBottom);
    }

    /** 本轮面板的几何与滚动结果，调用方保存起来给滚轮/输入用。 */
    public static final class Layout {
        public int panelX;
        public int panelY;
        public int panelRight;
        public int panelBottom;
        public int contentTop;
        public int contentBottom;
        public int contentHeight;
        public double scroll;

        public int viewHeight() {
            return contentBottom - contentTop;
        }

        public boolean scrollable() {
            return contentHeight > viewHeight();
        }
    }

    private ResUiOverlayPanel() {
    }

    /**
     * draw: 画面板。返回本轮布局（调用方保存 scroll 等状态）。
     *
     * @param preferredWidth  期望宽度（会按窗口缩小）
     * @param preferredHeight 期望高度（会按窗口缩小）
     * @param scroll          调用方当前的滚动位置（会被夹到合法范围）
     * @param onClose         点「x」时回调
     */
    public static Layout draw(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                              String title, int preferredWidth, int preferredHeight,
                              double scroll, Clickable clickable, Inside inside,
                              Content content, Runnable onClose) {
        Layout layout = new Layout();
        graphics.fill(0, 0, screen.width, screen.height, 0x99000000);

        int panelWidth = Math.min(preferredWidth, screen.width - 40);
        int panelHeight = Math.min(preferredHeight, screen.height - 40);
        int panelX = (screen.width - panelWidth) / 2;
        int panelY = (screen.height - panelHeight) / 2;
        int panelRight = panelX + panelWidth;
        int panelBottom = panelY + panelHeight;

        graphics.fill(panelX, panelY, panelRight, panelBottom, 0xF0202020);
        border(graphics, panelX, panelY, panelRight, panelBottom);
        graphics.fill(panelX, panelY, panelRight, panelY + TITLE_BAR_HEIGHT, COLOR_TITLE_BAR);
        graphics.drawString(screen.getFont(), title, panelX + 8, panelY + 8, COLOR_TEXT, true);

        // 关闭按钮（属于面板框架，不随内容滚动）
        int closeX = panelRight - PAD - CLOSE_SIZE;
        int closeY = panelY + 3;
        boolean closeHover = inside.test(mouseX, mouseY, closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE);
        graphics.fill(closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE,
                closeHover ? COLOR_BTN_HOVER : COLOR_BTN);
        border(graphics, closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE);
        graphics.drawString(screen.getFont(), "x", closeX + 6, closeY + 5, COLOR_TEXT, true);
        clickable.add(closeX, closeY, closeX + CLOSE_SIZE, closeY + CLOSE_SIZE, onClose);

        int contentX = panelX + 10;
        int contentRight = panelRight - 10;
        int contentTop = panelY + TITLE_BAR_HEIGHT + 4;
        int contentBottom = panelBottom - 6;

        // 先把滚动夹到合法范围要等知道内容高度，这里先按旧高度画，画完再夹一次
        graphics.enableScissor(contentX, contentTop, contentRight, contentBottom);
        int contentHeight = content.draw(graphics, mouseX, mouseY,
                contentX, contentTop - (int) scroll, contentRight, contentBottom);
        graphics.disableScissor();

        layout.panelX = panelX;
        layout.panelY = panelY;
        layout.panelRight = panelRight;
        layout.panelBottom = panelBottom;
        layout.contentTop = contentTop;
        layout.contentBottom = contentBottom;
        layout.contentHeight = contentHeight;
        double maxScroll = Math.max(0, contentHeight - (contentBottom - contentTop));
        layout.scroll = Math.max(0.0D, Math.min(maxScroll, scroll));

        if (layout.scrollable()) {
            graphics.drawString(screen.getFont(), "滚轮查看更多", contentX, contentBottom - 10, COLOR_MUTED, true);
        }
        return layout;
    }

    private static void border(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y1 + 1, COLOR_BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, COLOR_BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, COLOR_BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, COLOR_BORDER);
    }

    /** 供内容使用的通用按钮（与两个界面同风格）。 */
    public static int button(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                             Clickable clickable, Inside inside, int contentTop, int contentBottom,
                             int x, int y, String label, boolean active, Runnable action) {
        int width = 14 + screen.getFont().width(label);
        boolean hover = inside.test(mouseX, mouseY, x, y, x + width, y + 18);
        int background = hover ? COLOR_BTN_HOVER : COLOR_BTN;
        graphics.fill(x, y, x + width, y + 18, background);
        border(graphics, x, y, x + width, y + 18);
        graphics.drawString(screen.getFont(), label, x + (width - screen.getFont().width(label)) / 2,
                y + 5, active ? COLOR_TEXT : COLOR_MUTED, true);
        if (y + 18 > contentTop && y < contentBottom) {
            clickable.add(x, y, x + width, y + 18, action);
        }
        return x + width + 4;
    }

    /** 输入框（只画 + 注册焦点，不处理按键）。 */
    public static int field(Screen screen, GuiGraphics graphics, Clickable clickable, Inside inside,
                            int x, int y, int width, String text, String hint, boolean focused,
                            Consumer<Void> onFocus) {
        int height = 16;
        graphics.fill(x, y, x + width, y + height, focused ? 0xFF101010 : 0xFF1A1A1A);
        border(graphics, x, y, x + width, y + height);
        boolean empty = text == null || text.isEmpty();
        graphics.drawString(screen.getFont(), empty ? hint : text, x + 4, y + (height - 8) / 2 + 1,
                empty ? COLOR_MUTED : COLOR_TEXT, true);
        if (focused) {
            int caretX = x + 4 + screen.getFont().width(text);
            graphics.fill(caretX + 1, y + 3, caretX + 2, y + height - 3, 0xFFDDDDDD);
        }
        clickable.add(x, y, x + width, y + height, () -> onFocus.accept(null));
        return y + height + 2;
    }

    /** 小节标题（带分隔线）。 */
    public static int section(Screen screen, GuiGraphics graphics, int x, int y, String title) {
        graphics.drawString(screen.getFont(), title, x, y, COLOR_TEXT, true);
        graphics.fill(x, y + 11, x + 220, y + 12, COLOR_BORDER);
        return y + 18;
    }

    /** 说明文字。 */
    public static int hint(Screen screen, GuiGraphics graphics, int x, int y, String text) {
        graphics.drawString(screen.getFont(), text, x, y, COLOR_MUTED, true);
        return y + 12;
    }
}
