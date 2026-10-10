package com.resui.client.residence;

import com.resui.client.ResUiClient;
import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * ResidenceListScreen: 展示服务端（Residence 插件）推来的领地数据。
 * 两个分类：自己的领地 / 公共可传送领地；带刷新与传送按钮。
 */
@Environment(EnvType.CLIENT)
public final class ResidenceListScreen extends Screen {
    private static final int COLOR_SCRIM = 0xFF444444;
    private static final int COLOR_TITLE_BAR = 0xFF303030;
    private static final int COLOR_ROW = 0x55202020;
    private static final int COLOR_ROW_ALT = 0x33202020;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_BTN = 0xFF565656;
    private static final int COLOR_BTN_HOVER = 0xFF6C6C6C;
    private static final int COLOR_TAB_ACTIVE = 0xFF6E6E6E;
    private static final int COLOR_TAB = 0xFF3F3F3F;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;
    private static final int COLOR_ERROR = 0xFFFF7777;
    private static final int COLOR_OK = 0xFF6EE06E;
    /** 界面背景图（原图为暗色调壁纸，铺满后压一层半透明黑保证文字可读）。 */
    private static final Identifier BACKGROUND =
            Identifier.fromNamespaceAndPath("resui", "textures/gui/background.png");
    /** 背景图实际像素尺寸（绘制时按纹理尺寸归一化 UV）。 */
    private static final int BACKGROUND_W = 1280;
    private static final int BACKGROUND_H = 720;

    private static final int PAD = 8;
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int GAP = 5;
    private static final int TAB_H = 20;
    private static final int ROW_H = 26;
    private static final int BTN_H = 16;
    private static final int CLOSE_SIZE = 18;

    private final List<Clickable> clickables = new ArrayList<>();
    private ResidenceEntry detailEntry;
    private int tab;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private double scroll;
    private boolean scrollBarVisible;
    private boolean draggingScroll;
    private int scrollBarX;
    private int scrollBarTop;
    private int scrollBarBottom;
    private int scrollBarKnobHeight;

    public ResidenceListScreen() {
        super(Component.literal("领地列表"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawBackground(graphics);
        // 只压很轻的一层，保证壁纸可见（文字可读性靠面板自身的半透明底）
        graphics.fill(0, 0, this.width, this.height, 0x22000000);
        int left = PAD;
        int top = PAD;
        int right = this.width - PAD;
        int bottom = this.height - PAD;

        graphics.fill(left, top, right, top + TITLE_BAR_HEIGHT, COLOR_TITLE_BAR);
        graphics.drawString(this.font, this.title, left + 8, top + 8, COLOR_TEXT, true);

        clickables.clear();
        drawCloseButton(graphics, mouseX, mouseY, right - PAD - CLOSE_SIZE, top + 3);

        int tabsTop = top + TITLE_BAR_HEIGHT + GAP;
        drawTabs(graphics, mouseX, mouseY, left + GAP, tabsTop, right - GAP);

        this.contentTop = tabsTop + TAB_H + GAP;
        this.contentBottom = bottom - GAP;
        graphics.enableScissor(left + GAP, contentTop, right - GAP, contentBottom);
        int endY = drawList(graphics, mouseX, mouseY, left + GAP, contentTop - (int) scroll, right - GAP);
        graphics.disableScissor();
        this.contentHeight = endY - contentTop + (int) scroll;
        drawScrollBar(graphics, right - GAP);

        if (detailEntry != null) {
            // 弹窗打开时屏蔽列表交互，只保留弹窗内的按钮
            clickables.clear();
            drawDetailOverlay(graphics, mouseX, mouseY);
        }
        drawFeedback(graphics);
    }

    /** drawBackground: 背景图按「保持宽高比铺满」绘制，窗口尺寸变化时自动缩放（多余部分裁掉）。 */
    private void drawBackground(GuiGraphics graphics) {
        float scale = Math.max((float) this.width / BACKGROUND_W, (float) this.height / BACKGROUND_H);
        int drawWidth = Math.max(1, (int) (BACKGROUND_W * scale));
        int drawHeight = Math.max(1, (int) (BACKGROUND_H * scale));
        int x = (this.width - drawWidth) / 2;
        int y = (this.height - drawHeight) / 2;
        // 该重载参数顺序为 (x, y, u, v, 源宽, 源高, 目标宽, 目标高)
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                x, y, 0.0F, 0.0F, BACKGROUND_W, BACKGROUND_H, drawWidth, drawHeight);
    }

    /** drawFeedback: 底部居中的操作结果提示条（成功绿 / 失败红，约 4 秒后消失）。 */
    private void drawFeedback(GuiGraphics graphics) {
        String feedback = ResidenceCache.INSTANCE.feedback();
        if (feedback.isBlank()) {
            return;
        }
        int textWidth = this.font.width(feedback);
        int x = (this.width - textWidth) / 2;
        int y = this.height - PAD - 12;
        graphics.fill(x - 6, y - 3, x + textWidth + 6, y + 11, 0xCC000000);
        graphics.drawString(this.font, feedback, x, y,
                ResidenceCache.INSTANCE.feedbackOk() ? COLOR_OK : COLOR_ERROR, true);
    }

    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceCache cache = ResidenceCache.INSTANCE;
        int ownWidth = 130;
        int publicWidth = 150;
        drawButton(graphics, mouseX, mouseY, x, y, ownWidth, TAB_H,
                "我的领地 " + cache.own().size(), tab == 0, false, () -> {
                    tab = 0;
                    scroll = 0;
                });
        drawButton(graphics, mouseX, mouseY, x + ownWidth + 4, y, publicWidth, TAB_H,
                "公共领地 " + cache.publicList().size(), tab == 1, false, () -> {
                    tab = 1;
                    scroll = 0;
                });
        int refreshWidth = 56;
        drawButton(graphics, mouseX, mouseY, right - refreshWidth, y, refreshWidth, TAB_H,
                "刷新", true, false, ResUiClient::requestRefresh);
    }

    private int drawList(GuiGraphics graphics, int mouseX, int mouseY, int x1, int y, int x2) {
        ResidenceCache cache = ResidenceCache.INSTANCE;
        if (!cache.error().isBlank()) {
            graphics.drawString(this.font, cache.error(), x1, y, COLOR_ERROR, true);
            y += ROW_H;
        }
        List<ResidenceEntry> entries = tab == 0 ? cache.own() : cache.publicList();
        if (entries.isEmpty()) {
            String hint = cache.isEmpty()
                    ? "暂无数据：点击右上角“刷新”，或输入 /resbridge 让服务器推送"
                    : "该分类暂无领地";
            graphics.drawString(this.font, hint, x1, y, COLOR_MUTED, true);
            return y + ROW_H;
        }
        int index = 0;
        for (ResidenceEntry entry : entries) {
            y = drawRow(graphics, mouseX, mouseY, x1, y, x2, entry, index % 2 == 0);
            index++;
        }
        return y;
    }

    private int drawRow(GuiGraphics graphics, int mouseX, int mouseY,
                        int x1, int y, int x2, ResidenceEntry entry, boolean alternate) {
        graphics.fill(x1, y, x2, y + ROW_H, alternate ? COLOR_ROW : COLOR_ROW_ALT);
        drawBorder(graphics, x1, y, x2, y + ROW_H);

        String name = (entry.subzone() ? "[子区] " : "") + entry.name();
        graphics.drawString(this.font, name, x1 + 6, y + 4, COLOR_TEXT, true);
        String info = entry.infoText();
        if (!info.isBlank()) {
            graphics.drawString(this.font, info, x1 + 6, y + 15, COLOR_MUTED, true);
        }
        // 按钮从右往左排：传送 → 详细 → 管理（管理只在“我的领地”里出现）
        int buttonWidth = 52;
        int gap = 4;
        int cursor = x2 - gap - buttonWidth;
        if (entry.tp()) {
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    "传送", true, true, () -> {
                        ResUiClient.teleport(entry.name());
                        this.onClose();
                    });
            cursor -= buttonWidth + gap;
        }
        drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                "详细", true, true, () -> detailEntry = entry);
        cursor -= buttonWidth + gap;
        if (tab == 0) {
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    "管理", true, true, () -> Minecraft.getInstance().setScreen(new ResidenceManageScreen(entry)));
        }
        return y + ROW_H + 3;
    }

    /** drawDetailOverlay: 领地详细信息弹窗（坐标等细节都在这里）。 */
    private void drawDetailOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        ResidenceEntry entry = detailEntry;
        int panelWidth = 300;
        int panelHeight = 162;
        int x1 = (this.width - panelWidth) / 2;
        int y1 = (this.height - panelHeight) / 2;
        int x2 = x1 + panelWidth;
        int y2 = y1 + panelHeight;

        // 全屏遮罩：点击外部关闭
        clickables.add(new Clickable(0, 0, this.width, this.height, true, false, () -> detailEntry = null));
        graphics.fill(0, 0, this.width, this.height, 0x99000000);

        graphics.fill(x1, y1, x2, y2, 0xF0202020);
        drawBorder(graphics, x1, y1, x2, y2);
        graphics.drawString(this.font, entry.name(), x1 + 10, y1 + 8, COLOR_TEXT, true);

        int lineY = y1 + 26;
        lineY = detailLine(graphics, x1 + 10, lineY, "所有者：", entry.owner().isBlank() ? "未知" : entry.owner());
        lineY = detailLine(graphics, x1 + 10, lineY, "世界：", entry.world().isBlank() ? "未知" : entry.world());
        lineY = detailLine(graphics, x1 + 10, lineY, "坐标范围：", entry.areaText());
        lineY = detailLine(graphics, x1 + 10, lineY, "领地大小：", entry.sizeText());
        lineY = detailLine(graphics, x1 + 10, lineY, "领地价值：", entry.worthText());
        lineY = detailLine(graphics, x1 + 10, lineY,
                "区域数：", String.valueOf(Math.max(1, entry.areas())) + (entry.subzone() ? "（子区域）" : ""));
        detailLine(graphics, x1 + 10, lineY,
                "可传送：", entry.tp() ? "是" : "否");

        int buttonY = y2 - 26;
        int buttonX = x1 + 10;
        if (entry.tp()) {
            drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "传送", true, false, () -> {
                ResUiClient.teleport(entry.name());
                this.onClose();
            });
            buttonX += 56;
        }
        drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "关闭", true, false,
                () -> detailEntry = null);
    }

    private int detailLine(GuiGraphics graphics, int x, int y, String label, String value) {
        graphics.drawString(this.font, label, x, y, COLOR_MUTED, true);
        graphics.drawString(this.font, value, x + this.font.width(label), y, COLOR_TEXT, true);
        return y + 12;
    }

    private void drawCloseButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + CLOSE_SIZE, y + CLOSE_SIZE);
        graphics.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, hover ? COLOR_BTN_HOVER : COLOR_BTN);
        drawBorder(graphics, x, y, x + CLOSE_SIZE, y + CLOSE_SIZE);
        graphics.drawString(this.font, "x", x + 6, y + 5, COLOR_TEXT, true);
        clickables.add(new Clickable(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, true, false, this::onClose));
    }

    private void drawButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width, int height,
                            String label, boolean active, boolean inContent, Runnable action) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + width, y + height);
        int background;
        if (!active) {
            background = COLOR_TAB;
        } else if (hover) {
            background = COLOR_BTN_HOVER;
        } else {
            background = tab == 1 && label.startsWith("公共") || tab == 0 && label.startsWith("我的")
                    ? COLOR_TAB_ACTIVE
                    : COLOR_BTN;
        }
        graphics.fill(x, y, x + width, y + height, background);
        drawBorder(graphics, x, y, x + width, y + height);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (width - textWidth) / 2,
                y + (height - 8) / 2, COLOR_TEXT, true);
        clickables.add(new Clickable(x, y, x + width, y + height, true, inContent, action));
    }

    private void drawScrollBar(GuiGraphics graphics, int x) {
        int viewHeight = contentBottom - contentTop;
        scrollBarVisible = contentHeight > viewHeight;
        if (!scrollBarVisible) {
            return;
        }
        int barX = x + 1;
        graphics.fill(barX, contentTop, barX + 3, contentBottom, 0x40000000);
        int knobHeight = Math.max(16, viewHeight * viewHeight / contentHeight);
        int maxScroll = contentHeight - viewHeight;
        int knobY = contentTop + (int) ((viewHeight - knobHeight) * (scroll / maxScroll));
        graphics.fill(barX, knobY, barX + 3, knobY + knobHeight, 0xFF9A9A9A);
        // 记录几何信息，供鼠标拖拽使用
        this.scrollBarX = barX;
        this.scrollBarTop = contentTop;
        this.scrollBarBottom = contentBottom;
        this.scrollBarKnobHeight = knobHeight;
    }

    /** scrollFromMouse: 按鼠标 Y 计算并设置滚动位置（拖动滚动条时用）。 */
    private void scrollFromMouse(double mouseY) {
        int viewHeight = contentBottom - contentTop;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        int movable = viewHeight - scrollBarKnobHeight;
        if (maxScroll <= 0 || movable <= 0) {
            return;
        }
        double ratio = (mouseY - scrollBarTop - scrollBarKnobHeight / 2.0D) / movable;
        scroll = Math.max(0.0D, Math.min(maxScroll, ratio * maxScroll));
    }

    private void drawBorder(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y1 + 1, COLOR_BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, COLOR_BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, COLOR_BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, COLOR_BORDER);
    }

    private static boolean isInside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() == 0 && scrollBarVisible
                && isInside(mouseX, mouseY, scrollBarX - 4, scrollBarTop, scrollBarX + 8, scrollBarBottom)) {
            draggingScroll = true;
            scrollFromMouse(mouseY);
            return true;
        }
        if (event.button() == 0) {
            boolean insideContent = mouseY >= contentTop && mouseY < contentBottom;
            for (int i = clickables.size() - 1; i >= 0; i--) {
                Clickable clickable = clickables.get(i);
                if (!clickable.enabled || (clickable.inContent && !insideContent)) {
                    continue;
                }
                if (isInside(mouseX, mouseY, clickable.x1, clickable.y1, clickable.x2, clickable.y2)) {
                    clickable.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScroll) {
            scrollFromMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int viewHeight = contentBottom - contentTop;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        scroll = Math.max(0.0D, Math.min(maxScroll, scroll - scrollY * 20.0D));
        return true;
    }

    private record Clickable(int x1, int y1, int x2, int y2, boolean enabled, boolean inContent, Runnable action) {
    }
}