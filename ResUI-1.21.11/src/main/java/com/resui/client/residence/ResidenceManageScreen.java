package com.resui.client.residence;

import com.resui.client.ResUiClient;
import com.resui.client.bridge.FlagOption;
import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceEntry;
import com.resui.client.bridge.ResidenceMember;
import com.resui.client.bridge.ResidenceMe;
import com.resui.client.bridge.ResidenceSwitches;
import com.resui.client.config.ResUiBackgrounds;
import com.resui.client.config.ResUiConfig;
import com.resui.client.config.ResUiSettingsPanel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ResidenceManageScreen: 领地管理面板。
 * 左侧导航（领地信息 / 权限管理 / 领地成员），右侧为对应内容。
 * 仅对自己的领地开放（由列表页的“管理”按钮进入）。
 */
@Environment(EnvType.CLIENT)
public final class ResidenceManageScreen extends Screen {
    private static final int COLOR_SCRIM = 0xFF444444;
    private static final int COLOR_TITLE_BAR = 0xFF303030;
    private static final int COLOR_PANEL = 0x55202020;
    private static final int COLOR_ROW = 0x55202020;
    private static final int COLOR_ROW_ALT = 0x33202020;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_BTN = 0xFF565656;
    private static final int COLOR_BTN_HOVER = 0xFF6C6C6C;
    private static final int COLOR_TAB = 0xFF3F3F3F;
    private static final int COLOR_TAB_ACTIVE = 0xFF6E6E6E;
    private static final int COLOR_ON = 0xFF2E7D32;
    private static final int COLOR_ON_HOVER = 0xFF3FA34D;
    private static final int COLOR_OFF = 0xFF6B2F2F;
    private static final int COLOR_OFF_HOVER = 0xFF8A4A4A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;

    // ---------------------------------------------------------------- 导航页定义
    // 左侧导航是数据驱动的：加一个页面 = 加一个常量 + 在 SIDEBAR_ITEMS 里加一行 + 在 render 里加一个分支。
    private static final int PAGE_INFO = 0;
    private static final int PAGE_FLAGS = 1;
    private static final int PAGE_MEMBERS = 2;
    private static final int PAGE_AREAS = 3;
    private static final int PAGE_BOUNDS = 4;
    private static final int PAGE_LISTS = 5;
    private static final int PAGE_MESSAGES = 6;
    private static final int PAGE_COMMANDS = 7;
    private static final int PAGE_BANK = 8;
    private static final int PAGE_MARKET = 9;
    private static final int PAGE_CHAT = 10;
    private static final int PAGE_ME = 11;
    private static final int PAGE_SETTINGS = 12;

    /** 侧栏条目（显示名 + 页号）。 */
    private record NavItem(String label, int page) {
    }

    private static final List<NavItem> SIDEBAR_ITEMS = List.of(
            new NavItem("领地信息", PAGE_INFO),
            new NavItem("区域子领地", PAGE_AREAS),
            new NavItem("边界与杂项", PAGE_BOUNDS),
            new NavItem("权限管理", PAGE_FLAGS),
            new NavItem("领地成员", PAGE_MEMBERS),
            new NavItem("名单管理", PAGE_LISTS),
            new NavItem("进出消息", PAGE_MESSAGES),
            new NavItem("命令拦截", PAGE_COMMANDS),
            new NavItem("领地银行", PAGE_BANK),
            new NavItem("市场出租", PAGE_MARKET),
            new NavItem("领地聊天", PAGE_CHAT),
            new NavItem("我的", PAGE_ME));

    private static final int PAD = 8;
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int GAP = 5;
    private static final int CLOSE_SIZE = 18;
    private static final int SIDEBAR_W = 100;
    private static final int NAV_H = 22;
    private static final int BTN_H = 18;
    private static final int FLAG_ROW_H = 22;
    private static final int TOGGLE_W = 48;
    private static final int TOGGLE_H = 18;
    private static final int TRI_TOGGLE_W = 56;
    private static final int ROW_H = 22;
    /** 自绘搜索框：高度与宽度都刻意做得很紧凑。 */
    private static final int SEARCH_H = 13;
    private static final int PLAYER_SEARCH_W = 90;
    private static final int FLAG_SEARCH_W = 110;
    private static final int COLOR_DEFAULT = 0xFF4A4A4A;
    private static final int COLOR_DEFAULT_HOVER = 0xFF5E5E5E;
    private static final int COLOR_FEEDBACK_OK = 0xFF6EE06E;
    private static final int COLOR_FEEDBACK_FAIL = 0xFFFF7777;
    /** 危险操作 / 二次确认弹窗用的红色。 */
    private static final int COLOR_DANGER = 0xFFB03030;
    /** 界面背景图：实际用哪张由 ResUiBackgrounds 决定（内置图或玩家自定义图）。 */

    private final List<Clickable> clickables = new ArrayList<>();
    private final ResidenceEntry fallbackEntry;
    private int page;
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
    /** 成员页里当前正在编辑权限的玩家名；为 null 时显示成员列表。 */
    private String selectedMember;
    /** 是否处于“选择在线玩家”页面。 */
    private boolean pickingPlayer;
    /** 自绘搜索框的过滤词（分别用于在线玩家列表 / 权限列表）。 */
    private String playerFilter = "";
    private String flagFilter = "";
    /** 当前获得键盘焦点的输入框 key（null = 没有焦点）；输入内容存在 {@link #fieldValues} 里。 */
    private String focusedField;
    private final java.util.Map<String, String> fieldValues = new java.util.HashMap<>();
    /** 待确认的危险操作（非 null 时渲染红字确认弹窗并屏蔽底层交互）。 */
    private Confirm pendingConfirm;

    /** Confirm: 二次确认弹窗的内容与确认后要执行的动作。 */
    private record Confirm(String title, String detail, String confirmLabel, Runnable action) {
    }
    /** 消息页 / 名单页共用的输入框尺寸。 */
    private static final int FIELD_H = 16;
    private static final int FIELD_W = 200;
    private static final int MAX_FIELD_LENGTH = 256;

    public ResidenceManageScreen(ResidenceEntry entry) {
        super(Component.literal("领地管理"));
        this.fallbackEntry = entry;
    }

    @Override
    protected void init() {
        // 首帧就把输入框预填好（切页时会再清、再填）
        fieldValues.clear();
        prepareFields();
    }

    /** field: 读取某个输入框当前的内容（不存在时为空串）。 */
    private String field(String key) {
        return fieldValues.getOrDefault(key, "");
    }

    /** setField: 写入某个输入框的内容。 */
    private void setField(String key, String value) {
        fieldValues.put(key, value == null ? "" : value);
    }

    /** isTyping: 是否有输入框正在接收键盘（此时不把按键交给原版，避免触发快捷键）。 */
    private boolean isTyping() {
        return focusedField != null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** entry: 优先用缓存里的最新数据（切换权限后刷新会更新）。 */
    private ResidenceEntry entry() {
        for (ResidenceEntry candidate : ResidenceCache.INSTANCE.own()) {
            if (candidate.name().equals(fallbackEntry.name())) {
                return candidate;
            }
        }
        return fallbackEntry;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new ResidenceListScreen());
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
        graphics.drawString(this.font, "管理：" + entry().name(), left + 8, top + 8, COLOR_TEXT, true);

        clickables.clear();
        scrollBarVisible = false;
        drawCloseButton(graphics, mouseX, mouseY, right - PAD - CLOSE_SIZE, top + 3);

        int sidebarX = left + GAP;
        int bodyY = top + TITLE_BAR_HEIGHT + GAP;
        int bodyBottom = bottom - GAP;

        // 左侧导航
        graphics.fill(sidebarX, bodyY, sidebarX + SIDEBAR_W, bodyBottom, COLOR_PANEL);
        drawBorder(graphics, sidebarX, bodyY, sidebarX + SIDEBAR_W, bodyBottom);
        drawSidebar(graphics, mouseX, mouseY, sidebarX + 6, bodyY + 6);

        // 右侧内容
        int contentX = sidebarX + SIDEBAR_W + GAP;
        int contentRight = right - GAP;
        this.contentTop = bodyY;
        this.contentBottom = bodyBottom;
        if (page == PAGE_INFO || page == PAGE_AREAS) {
            // 这两页内容较长（信息页还带危险操作区），同样支持滚动
            graphics.enableScissor(contentX, bodyY + 2, contentRight, contentBottom);
            int endY = (page == PAGE_INFO)
                    ? drawInfoPage(graphics, mouseX, mouseY, contentX + 8, bodyY + 8 - (int) scroll, contentRight - 8)
                    : drawAreasPage(graphics, mouseX, mouseY, contentX + 8, bodyY + 8 - (int) scroll, contentRight - 16);
            graphics.disableScissor();
            this.contentHeight = endY - (bodyY + 8) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        } else if (page == PAGE_MEMBERS && pickingPlayer) {
            // 选择在线玩家：顶部搜索栏固定，玩家列表滚动
            int listTop = drawPickerHeader(graphics, mouseX, mouseY, partialTick,
                    contentX + 8, contentTop + 6, contentRight - 8);
            this.contentTop = listTop;
            graphics.enableScissor(contentX, listTop, contentRight, contentBottom);
            int endY = drawOnlinePlayers(graphics, mouseX, mouseY,
                    contentX + 8, listTop + 2 - (int) scroll, contentRight - 16);
            graphics.disableScissor();
            this.contentHeight = endY - (listTop + 2) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        } else if (page == PAGE_MEMBERS && selectedMember == null) {
            // 成员列表：工具条固定在顶部，列表滚动
            int listTop = drawMembersToolbar(graphics, mouseX, mouseY, partialTick,
                    contentX + 8, contentTop + 6, contentRight - 8);
            this.contentTop = listTop;
            graphics.enableScissor(contentX, listTop, contentRight, contentBottom);
            int endY = drawMembersList(graphics, mouseX, mouseY,
                    contentX + 8, listTop + 2 - (int) scroll, contentRight - 16);
            graphics.disableScissor();
            this.contentHeight = endY - (listTop + 2) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        } else if (page == PAGE_MEMBERS) {
            // 单个成员的权限：顶部返回栏 + 搜索栏固定，权限列表滚动
            int headerTop = drawMemberHeader(graphics, mouseX, mouseY,
                    contentX + 8, contentTop + 6, contentRight - 8);
            int listTop = drawFlagSearchHeader(graphics, mouseX, mouseY, partialTick,
                    contentX + 8, headerTop, contentRight - 8);
            this.contentTop = listTop;
            graphics.enableScissor(contentX, listTop, contentRight, contentBottom);
            int endY = drawMemberFlags(graphics, mouseX, mouseY,
                    contentX + 8, listTop + 2 - (int) scroll, contentRight - 16);
            graphics.disableScissor();
            this.contentHeight = endY - (listTop + 2) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        } else if (page == PAGE_FLAGS) {
            // 领地权限：顶部搜索栏固定，权限列表滚动
            int listTop = drawFlagSearchHeader(graphics, mouseX, mouseY, partialTick,
                    contentX + 8, contentTop + 6, contentRight - 8);
            this.contentTop = listTop;
            graphics.enableScissor(contentX, listTop, contentRight, contentBottom);
            int endY = drawFlagsPage(graphics, mouseX, mouseY,
                    contentX + 8, listTop + 2 - (int) scroll, contentRight - 16);
            graphics.disableScissor();
            this.contentHeight = endY - (listTop + 2) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        } else {
            // 其余页面：各自实现滚动（沿用同一套 scissor + 滚动条）
            int endY = drawPage(graphics, mouseX, mouseY, partialTick,
                    contentX + 8, contentTop + 2 - (int) scroll, contentRight - 16);
            this.contentHeight = endY - (contentTop + 2) + (int) scroll;
            drawScrollBar(graphics, contentRight - 6);
        }
        // 危险操作的二次确认弹窗：画在最后，并且清掉底层控件的点击（避免误点）
        if (pendingConfirm != null) {
            clickables.clear();
        }
        drawConfirm(graphics, mouseX, mouseY);
        drawFeedback(graphics);
    }

    /** drawSidebar: 逐个画侧栏条目（一律置顶，不随内容滚动）。 */
    private void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY, int x, int startY) {
        int count = SIDEBAR_ITEMS.size();
        int available = Math.max(40, contentBottom - startY - 34);
        // 条目多 / 窗口小的时候自动压缩间距，避免溢出面板
        int step = Math.max(15, Math.min(NAV_H + 4, available / Math.max(1, count)));
        int itemHeight = Math.min(NAV_H, step - 1);
        int y = startY;
        for (NavItem item : SIDEBAR_ITEMS) {
            drawNavButton(graphics, mouseX, mouseY, x, y, SIDEBAR_W - 12, itemHeight, item.label(), item.page());
            y += step;
        }
        // 当前所在地：只在侧栏还有空间时画
        String current = ResidenceCache.INSTANCE.me().current();
        if (!current.isBlank() && y + 30 <= contentBottom) {
            graphics.drawString(this.font, "所在地:", x, y + 2, COLOR_MUTED, true);
            graphics.drawString(this.font, trimToWidth(current, SIDEBAR_W - 12), x, y + 14, COLOR_TEXT, true);
        }
    }

    /** trimToWidth: 侧栏宽度有限，过长的领地名截断加省略号。 */
    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && this.font.width(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    /** drawPage: 新页面的统一入口，返回内容底部 y（供滚动条计算）。 */
    private int drawPage(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                         int x, int y, int right) {
        return switch (page) {
            case PAGE_AREAS -> drawAreasPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_BOUNDS -> drawBoundsPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_LISTS -> drawListsPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_MESSAGES -> drawMessagesPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_COMMANDS -> drawCommandsPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_BANK -> drawBankPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_MARKET -> drawMarketPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_CHAT -> drawChatPage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_ME -> drawMePage(graphics, mouseX, mouseY, x, y, right);
            case PAGE_SETTINGS -> drawSettingsPage(graphics, mouseX, mouseY, x, y, right);
            default -> y + ROW_H;
        };
    }

    /** drawFlagSearchHeader: 权限列表顶部搜索栏，返回列表起始 y。 */
    private int drawFlagSearchHeader(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                     int x, int y, int right) {
        drawSearchBox(graphics, x, y + 3, FLAG_SEARCH_W, flagFilter, "搜索权限");
        graphics.drawString(this.font,
                "共 " + ResidenceCache.INSTANCE.allFlags().size() + " 项权限，输入关键词筛选",
                x + FLAG_SEARCH_W + 10, y + 5, COLOR_MUTED, true);
        return y + BTN_H + 6;
    }

    /** drawBackground: 背景图按「保持宽高比铺满」绘制，窗口尺寸变化时自动缩放（多余部分裁掉）。 */
    private void drawBackground(GuiGraphics graphics) {
        // 自定义背景可用时用它，否则用内置图；两者都是「按宽高比铺满 + 居中裁切」，不会拉变形
        int sourceWidth = ResUiBackgrounds.INSTANCE.width();
        int sourceHeight = ResUiBackgrounds.INSTANCE.height();
        float scale = Math.max((float) this.width / sourceWidth, (float) this.height / sourceHeight);
        int drawWidth = Math.max(1, (int) (sourceWidth * scale));
        int drawHeight = Math.max(1, (int) (sourceHeight * scale));
        int x = (this.width - drawWidth) / 2;
        int y = (this.height - drawHeight) / 2;
        // 该重载参数顺序为 (x, y, u, v, 源宽, 源高, 目标宽, 目标高)
        graphics.blit(RenderPipelines.GUI_TEXTURED, ResUiBackgrounds.INSTANCE.background(),
                x, y, 0.0F, 0.0F, sourceWidth, sourceHeight, drawWidth, drawHeight);
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
                ResidenceCache.INSTANCE.feedbackOk() ? COLOR_FEEDBACK_OK : COLOR_FEEDBACK_FAIL, true);
    }

    /** drawInfoPage: 领地信息页（基本信息 + 公开/私有开关 + 危险操作区）。返回内容底部 y。 */
    private int drawInfoPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        int lineY = y;
        lineY = detailLine(graphics, x, lineY, "所有者：", entry.owner().isBlank() ? "未知" : entry.owner());
        lineY = detailLine(graphics, x, lineY, "世界：", entry.world().isBlank() ? "未知" : entry.world());
        lineY = detailLine(graphics, x, lineY, "坐标范围：", entry.areaText());
        lineY = detailLine(graphics, x, lineY, "领地大小：", entry.sizeText());
        lineY = detailLine(graphics, x, lineY, "领地价值：", entry.worthText());
        lineY = detailLine(graphics, x, lineY,
                "区域数：", String.valueOf(Math.max(1, entry.areas())) + (entry.subzone() ? "（子区域）" : ""));
        // 注意：entry.tp() 对自己的领地被服务端固定为 true（保证列表里始终能传送），
        // 这里显示的是领地 tp 权限的真实值，取自权限快照。
        lineY = detailLine(graphics, x, lineY,
                "可传送：", entry.flag("tp") ? "是（别人可 /res tp 到你的领地）" : "否（仅自己可传送）");
        if (entry.subzone()) {
            lineY = detailLine(graphics, x, lineY, "父领地：", entry.parent());
        }
        lineY = detailLine(graphics, x, lineY, "市场状态：", entry.marketText());
        if (!entry.leaseExpireText().isBlank()) {
            lineY = detailLine(graphics, x, lineY, "租约到期：", entry.leaseExpireText());
        }
        int buttonY = lineY + 6;
        int buttonW = 88;
        int gap = 8;
        int startX = x + Math.max(0, ((right - x) - (buttonW * 3 + gap * 2)) / 2);
        drawButton(graphics, mouseX, mouseY, startX, buttonY, buttonW, BTN_H, "设为公开", false,
                () -> ResUiClient.setTeleportable(entry().name(), true));
        drawButton(graphics, mouseX, mouseY, startX + buttonW + gap, buttonY, buttonW, BTN_H, "设为私有", false,
                () -> ResUiClient.setTeleportable(entry().name(), false));
        drawButton(graphics, mouseX, mouseY, startX + (buttonW + gap) * 2, buttonY, buttonW, BTN_H, "设置传送点", false,
                () -> ResUiClient.setTeleportPoint(entry().name()));
        return drawDangerZone(graphics, mouseX, mouseY, x, buttonY + BTN_H + 12, right);
    }

    /** drawDangerZone: 领地信息页底部的危险操作区（改名 / 扩缩 / 转让 / 删除）。 */
    private int drawDangerZone(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        int lineWidth = right - x;
        graphics.fill(x, y, right, y + 1, COLOR_DANGER);
        y += 6;
        graphics.drawString(this.font, "⚠ 危险操作（都会改结构，删除与转让无法撤销）", x, y, COLOR_DANGER, true);
        y += 14;
        // 重命名
        y = drawField(graphics, mouseX, mouseY, x, y, "rename.new", "新领地名");
        drawButton(graphics, mouseX, mouseY, x, y, 72, BTN_H, "重命名", true, () -> {
            String newName = field("rename.new").trim();
            if (newName.isEmpty()) {
                ResidenceCache.INSTANCE.setFeedback(false, "请先填写新领地名");
                return;
            }
            pendingConfirm = new Confirm("重命名领地为 " + newName,
                    "领地名字会立刻改变，其他玩家用旧名字的 /res tp 会失效。", "确认重命名",
                    () -> ResUiClient.renameResidence(entry.name(), newName));
        });
        y += BTN_H + 8;
        // 扩大 / 缩小（作用于指定区域，默认主区域）
        y = drawField(graphics, mouseX, mouseY, x, y, "resize.amount", "格数（1-1000）");
        drawField(graphics, mouseX, mouseY, x, y, "resize.area", "区域名（留空 = 主区域）");
        int resizeX = x;
        drawButton(graphics, mouseX, mouseY, resizeX, y, 72, BTN_H, "扩大", true,
                () -> requestResize("expand"));
        resizeX += 78;
        drawButton(graphics, mouseX, mouseY, resizeX, y, 72, BTN_H, "缩小", true,
                () -> requestResize("contract"));
        resizeX += 78;
        graphics.drawString(this.font, "扩大会按格数向四周外扩；缩小则整体内缩",
                resizeX, y + 5, COLOR_MUTED, true);
        y += BTN_H + 10;
        // 转让
        y = drawField(graphics, mouseX, mouseY, x, y, "give.player", "接收者玩家名");
        drawButton(graphics, mouseX, mouseY, x, y, 72, BTN_H, "转让领地", true, () -> {
            String target = field("give.player").trim();
            if (target.isEmpty()) {
                ResidenceCache.INSTANCE.setFeedback(false, "请先填写接收者玩家名");
                return;
            }
            pendingConfirm = new Confirm("把领地转让给 " + target,
                    "转让后你将失去管理权，对方成为新主人（权限设置保持不变）。这个操作无法自己撤销。",
                    "确认转让", () -> ResUiClient.giveResidence(entry.name(), target));
        });
        y += BTN_H + 10;
        // 删除
        drawButton(graphics, mouseX, mouseY, x, y, 120, BTN_H, "删除这块领地", true, () ->
                pendingConfirm = new Confirm("删除领地 " + entry.name(),
                        "领地会被立即删除，里面的保护全部消失，方块不受影响。这个操作无法撤销！",
                        "确认删除", () -> ResUiClient.removeResidence(entry.name())));
        y += BTN_H + 6;
        graphics.drawString(this.font, "删除后无法恢复；如果只是想让别人进不来，用上面的「设为私有」",
                x, y, COLOR_MUTED, true);
        return y + 16;
    }

    /** requestResize: 校验格数后弹二次确认，再发扩缩请求。 */
    private void requestResize(String mode) {
        int amount;
        try {
            amount = Integer.parseInt(field("resize.amount").trim());
        } catch (NumberFormatException invalid) {
            ResidenceCache.INSTANCE.setFeedback(false, "格数必须是 1-1000 的整数");
            return;
        }
        if (amount <= 0 || amount > 1000) {
            ResidenceCache.INSTANCE.setFeedback(false, "格数必须在 1-1000 之间");
            return;
        }
        String areaId = field("resize.area").trim();
        int finalAmount = amount;
        pendingConfirm = new Confirm(
                ("expand".equals(mode) ? "扩大 " : "缩小 ") + amount + " 格",
                "作用于区域「" + (areaId.isEmpty() ? "主区域" : areaId) + "」。"
                        + ("expand".equals(mode) ? "扩大会占用更多方块，可能与其他领地冲突。"
                        : "缩小可能把方块建筑暴露在保护之外。"),
                "确认" + ("expand".equals(mode) ? "扩大" : "缩小"),
                () -> ResUiClient.resizeResidence(entry().name(), mode, areaId, finalAmount));
    }

    /** drawConfirm: 红字二次确认弹窗（覆盖整屏，必须点其中一个按钮才能离开）。 */
    private void drawConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        Confirm confirm = pendingConfirm;
        if (confirm == null) {
            return;
        }
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
        int boxW = Math.min(360, this.width - 40);
        int boxH = 108;
        int boxX = (this.width - boxW) / 2;
        int boxY = (this.height - boxH) / 2;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF0202020);
        drawBorder(graphics, boxX, boxY, boxX + boxW, boxY + boxH);
        // 红色标题条
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 2, COLOR_DANGER);
        graphics.drawString(this.font, confirm.title(), boxX + 10, boxY + 12, COLOR_DANGER, true);
        int textY = boxY + 32;
        for (String line : wrap(confirm.detail(), boxW - 20)) {
            graphics.drawString(this.font, line, boxX + 10, textY, COLOR_TEXT, true);
            textY += 12;
        }
        int buttonW = 84;
        int gap = 10;
        int buttonsX = boxX + (boxW - (buttonW * 2 + gap)) / 2;
        int buttonY = boxY + boxH - BTN_H - 12;
        // 取消在左、确认在右（确认用红色，防止手滑）
        drawButton(graphics, mouseX, mouseY, buttonsX, buttonY, buttonW, BTN_H, "取消", false,
                () -> pendingConfirm = null);
        drawDangerButton(graphics, mouseX, mouseY, buttonsX + buttonW + gap, buttonY, buttonW, BTN_H,
                confirm.confirmLabel(), () -> {
                    Runnable action = confirm.action();
                    pendingConfirm = null;
                    action.run();
                });
    }

    /** drawDangerButton: 红色按钮（二次确认弹窗的“确认”用它）。 */
    private void drawDangerButton(GuiGraphics graphics, int mouseX, int mouseY,
                                  int x, int y, int width, int height, String label, Runnable action) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + height, hover ? 0xFFA33A3A : COLOR_DANGER);
        drawBorder(graphics, x, y, x + width, y + height);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (width - textWidth) / 2, y + (height - 8) / 2, COLOR_TEXT, true);
        clickables.add(new Clickable(x, y, x + width, y + height, true, false, action));
    }

    /** wrap: 极简按宽度折行（弹窗说明文字用）。 */
    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(current.toString() + c) > maxWidth) {
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

    // ---------------------------------------------------------------- 新页面（分阶段实现）
    // 约定：每个 drawXxxPage 返回内容底部 y，供滚动条计算；只读数据从 ResidenceCache.INSTANCE 取，
    // 写操作一律走 ResUiClient.xxx(...)（内部发 resui:action），结果由服务端随快照回执。

    /** drawField: 自绘输入框（FOCUS_H 高），点击获得焦点；返回下一行的 y。 */
    private int drawField(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, String key, String hint) {
        String text = field(key);
        boolean focused = key.equals(focusedField);
        int width = FIELD_W;
        graphics.fill(x, y, x + width, y + FIELD_H, focused ? 0xFF101010 : 0xFF1A1A1A);
        drawBorder(graphics, x, y, x + width, y + FIELD_H);
        boolean empty = text.isEmpty();
        graphics.drawString(this.font, empty ? hint : text, x + 4, y + (FIELD_H - 8) / 2 + 1,
                empty ? COLOR_MUTED : COLOR_TEXT, true);
        if (focused) {
            int caretX = x + 4 + this.font.width(text);
            graphics.fill(caretX + 1, y + 3, caretX + 2, y + FIELD_H - 3, 0xFFDDDDDD);
        }
        clickables.add(new Clickable(x, y, x + width, y + FIELD_H, true, true, () -> focusedField = key));
        return y + FIELD_H + 6;
    }

    /** drawPageTitle: 领地名标题行（新页面顶部统一用它）。 */
    private int drawPageTitle(GuiGraphics graphics, int x, int y, String title) {
        graphics.drawString(this.font, title, x, y, COLOR_TEXT, true);
        graphics.drawString(this.font, entry().name(), x + this.font.width(title) + 8, y, COLOR_MUTED, true);
        return y + 16;
    }

    /** drawHint: 一行灰色说明文字。 */
    private int drawHint(GuiGraphics graphics, int x, int y, String text) {
        graphics.drawString(this.font, text, x, y, COLOR_MUTED, true);
        return y + 12;
    }

    /** drawRow: 标准行底（隔行变色 + 描边），返回行内文字的 y。 */
    private int drawRow(GuiGraphics graphics, int x, int y, int right, int index, int height) {
        graphics.fill(x, y, right, y + height, index % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT);
        drawBorder(graphics, x, y, right, y + height);
        return y + (height - 8) / 2 + 1;
    }

    /** drawTextPage: 进出消息页。 */
    private int drawTextPage(GuiGraphics graphics, int mouseX, int mouseY,
                             int x, int y, int right, String title, String hint,
                             String fieldKey, java.util.function.Consumer<String> save) {
        y = drawPageTitle(graphics, x, y, title);
        y = drawHint(graphics, x, y, hint);
        y = drawField(graphics, mouseX, mouseY, x, y, fieldKey, "输入文字（& 颜色代码可用）");
        drawButton(graphics, mouseX, mouseY, x, y, 64, BTN_H, "保存", true,
                () -> save.accept(field(fieldKey)));
        drawButton(graphics, mouseX, mouseY, x + 70, y, 72, BTN_H, "清除", true,
                () -> {
                    setField(fieldKey, "");
                    save.accept("");
                });
        return y + BTN_H + 10;
    }

    /** drawMessagesPage: 进入 / 离开提示消息。 */
    private int drawMessagesPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        y = drawTextPage(graphics, mouseX, mouseY, x, y, right,
                "进入消息", "玩家踏进这块领地时显示的提示（留空 = 不显示）",
                "msg.enter", text -> ResUiClient.setMessage(entry.name(), "enter", text));
        y = drawTextPage(graphics, mouseX, mouseY, x, y, right,
                "离开消息", "玩家离开这块领地时显示的提示（留空 = 不显示）",
                "msg.leave", text -> ResUiClient.setMessage(entry.name(), "leave", text));
        if (!ResidenceCache.INSTANCE.me().limits().canSetMessage()) {
            y = drawHint(graphics, x, y + 2, "当前权限组不允许设置进出消息（Residence 的 canSetEnterLeaveMessages）");
        }
        return y + 10;
    }

    /** drawListsPage: 黑名单 / 忽略名单（按物品）。 */
    private int drawListsPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        y = drawPageTitle(graphics, x, y, "名单管理");
        y = drawHint(graphics, x, y, "黑名单 = 领地内禁止使用的物品；忽略名单 = 领地保护忽略这些物品");
        // 输入行：物品名（英文 ID，如 DIAMOND_SWORD）
        y = drawField(graphics, mouseX, mouseY, x, y, "list.item", "物品 ID，如 DIAMOND_SWORD");
        int buttonX = x;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 78, BTN_H, "加入黑名单", true,
                () -> ResUiClient.setListItem(entry.name(), "blacklist", field("list.item").trim(), false));
        buttonX += 84;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 78, BTN_H, "加入忽略名单", true,
                () -> ResUiClient.setListItem(entry.name(), "ignorelist", field("list.item").trim(), false));
        y += BTN_H + 10;
        // 两张表并排：左黑名单、右忽略名单
        int columnWidth = ((right - x) - 12) / 2;
        int leftY = drawItemTable(graphics, mouseX, mouseY, x, y, x + columnWidth, "黑名单", entry.blacklist(),
                item -> ResUiClient.setListItem(entry.name(), "blacklist", item.id(), true));
        int rightY = drawItemTable(graphics, mouseX, mouseY, x + columnWidth + 12, y, right, "忽略名单", entry.ignorelist(),
                item -> ResUiClient.setListItem(entry.name(), "ignorelist", item.id(), true));
        return Math.max(leftY, rightY) + 6;
    }

    /** drawItemTable: 一个小标题 + 一张物品表（每行右侧「移除」）。 */
    private int drawItemTable(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right,
                              String title, List<ResidenceEntry.ItemRef> items,
                              java.util.function.Consumer<ResidenceEntry.ItemRef> remove) {
        y = drawHint(graphics, x, y, title + "（" + items.size() + "）");
        if (items.isEmpty()) {
            return drawHint(graphics, x, y, "（空）") + 4;
        }
        int index = 0;
        for (ResidenceEntry.ItemRef item : items) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            graphics.drawString(this.font, trimToWidth(item.display(), right - x - 70), x + 6, textY, COLOR_TEXT, true);
            drawButton(graphics, mouseX, mouseY, right - 6 - 52, y + 2, 52, BTN_H, "移除", true,
                    () -> remove.accept(item));
            index++;
            y += ROW_H + 2;
        }
        return y;
    }

    /** drawCommandsPage: 领地内命令放行 / 禁用名单。 */
    private int drawCommandsPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        y = drawPageTitle(graphics, x, y, "命令拦截");
        y = drawHint(graphics, x, y, "需要领地权限里的 command 开关打开才生效；命令名不带 /，如 home、spawn");
        y = drawField(graphics, mouseX, mouseY, x, y, "cmd.name", "命令名，如 home");
        int buttonX = x;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 64, BTN_H, "禁用", true,
                () -> ResUiClient.setCommandRule(entry.name(), cleanCommand(field("cmd.name")), "block"));
        buttonX += 70;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 64, BTN_H, "放行", true,
                () -> ResUiClient.setCommandRule(entry.name(), cleanCommand(field("cmd.name")), "allow"));
        buttonX += 70;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 88, BTN_H, "从名单移除", true,
                () -> ResUiClient.setCommandRule(entry.name(), cleanCommand(field("cmd.name")), "remove"));
        y += BTN_H + 10;
        List<ResidenceEntry.CommandRef> commands = entry.commands();
        y = drawHint(graphics, x, y, "当前名单（" + commands.size() + "）");
        if (commands.isEmpty()) {
            return drawHint(graphics, x, y, "（空）") + 4;
        }
        int index = 0;
        for (ResidenceEntry.CommandRef command : commands) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            graphics.drawString(this.font, "/" + command.cmd(), x + 6, textY, COLOR_TEXT, true);
            graphics.drawString(this.font, command.blocked() ? "禁用" : "放行",
                    x + 12 + this.font.width("/" + command.cmd()), textY,
                    command.blocked() ? COLOR_FEEDBACK_FAIL : COLOR_FEEDBACK_OK, true);
            drawButton(graphics, mouseX, mouseY, right - 6 - 64, y + 2, 64, BTN_H, "改状态", true, () ->
                    ResUiClient.setCommandRule(entry.name(), command.cmd(), command.blocked() ? "allow" : "block"));
            index++;
            y += ROW_H + 2;
        }
        return y;
    }

    /** cleanCommand: 去掉玩家习惯性输入的斜杠与首尾空白（服务端还会再做一次校验）。 */
    private static String cleanCommand(String raw) {
        String cmd = raw == null ? "" : raw.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        return cmd;
    }

    /** drawBoundsPage: 当前所在 + 我的上限 + 脱困 / tpa（杂项页）。 */
    private int drawBoundsPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceMe me = ResidenceCache.INSTANCE.me();
        ResidenceMe.Limits limits = me.limits();
        y = drawPageTitle(graphics, x, y, "边界与杂项");
        y = detailLine(graphics, x, y, "当前所在：", me.currentText());
        y = detailLine(graphics, x, y, "权限组：", limits.group().isBlank() ? "未知" : limits.group());
        y = detailLine(graphics, x, y, "领地数量：", limits.maxResidences() < 0
                ? limits.usedResidences() + " / 无限制"
                : limits.usedResidences() + " / " + limits.maxResidences());
        y = detailLine(graphics, x, y, "还能新建：", limits.maxResidences() < 0 ? "无限制"
                : String.valueOf(Math.max(0, limits.maxResidences() - limits.usedResidences())));
        y = detailLine(graphics, x, y, "单区域上限：", limits.areaLimitText());
        y = detailLine(graphics, x, y, "单领地上限：", limits.physicalLimitText());
        y = detailLine(graphics, x, y, "子领地数量：", ResidenceMe.Limits.limitText(limits.maxSubzones()));
        y = detailLine(graphics, x, y, "子领地层数：", ResidenceMe.Limits.limitText(limits.maxSubzoneDepth()));
        y = detailLine(graphics, x, y, "可出租数量：", ResidenceMe.Limits.limitText(limits.maxRents()));
        y = detailLine(graphics, x, y, "租金上限天数：", ResidenceMe.Limits.limitText(limits.maxRentDays()));
        if (!me.money().isBlank()) {
            y = detailLine(graphics, x, y, "我的余额：", me.money());
        }
        y += 6;
        int buttonX = x;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "刷新上限", true,
                ResUiClient::refreshMe);
        buttonX += 102;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "查看边界", true,
                () -> ResUiClient.sendCommand("res show " + entry().name()));
        buttonX += 102;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 84, BTN_H, "卡住脱困", true,
                () -> ResUiClient.sendCommand("res unstuck"));
        y += BTN_H + 8;
        y = drawHint(graphics, x, y, "「查看边界」会在世界里用粒子画出领地范围（等价 /res show）");
        y = drawHint(graphics, x, y, "「卡住脱困」把你传回安全点（等价 /res unstuck，有权限组限制）");
        return y + 6;
    }

    /** drawBankPage: 领地银行（余额 / 存入 / 取出 / 出租相关设置）。 */
    private int drawBankPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        ResidenceSwitches switches = ResidenceCache.INSTANCE.switches();
        y = drawPageTitle(graphics, x, y, "领地银行");
        if (!switches.bank()) {
            return drawHint(graphics, x, y, "服务器未启用经济系统，领地银行不可用") + 6;
        }
        y = detailLine(graphics, x, y, "银行余额：", entry.bank().isBlank() ? "0" : entry.bank());
        y = detailLine(graphics, x, y, "容量上限：", switches.bankCapacityText());
        y = detailLine(graphics, x, y, "我的余额：", ResidenceCache.INSTANCE.me().money().isBlank()
                ? "经济未启用" : ResidenceCache.INSTANCE.me().money());
        y = detailLine(graphics, x, y, "租金扣款：", switches.leaseSystem()
                ? "租约已启用（详见市场页）" : "租金默认从玩家钱包扣");
        y += 6;
        y = drawField(graphics, mouseX, mouseY, x, y, "bank.amount", "金额（整数）");
        drawButton(graphics, mouseX, mouseY, x, y, 72, BTN_H, "存入", true, () -> bankAction("deposit"));
        drawButton(graphics, mouseX, mouseY, x + 78, y, 72, BTN_H, "取出", true, () -> bankAction("withdraw"));
        y += BTN_H + 10;
        y = drawHint(graphics, x, y, "领地银行可以给领地留一笔公共资金（比如一起攒钱扩大领地）");
        y = drawHint(graphics, x, y, "存入 / 取出都需要领地的 bank 权限；容量为 0 表示无上限");
        return y + 6;
    }

    /** bankAction: 客户端先做数值校验，避免把明显非法的请求发给服务端。 */
    private void bankAction(String mode) {
        int amount;
        try {
            amount = Integer.parseInt(field("bank.amount").trim());
        } catch (NumberFormatException invalid) {
            ResidenceCache.INSTANCE.setFeedback(false, "金额必须是大于 0 的整数");
            return;
        }
        if (amount <= 0) {
            ResidenceCache.INSTANCE.setFeedback(false, "金额必须是大于 0 的整数");
            return;
        }
        ResUiClient.bankAction(entry().name(), mode, amount);
    }

    /** drawMarketPage: 市场与出租（出售 / 招租 / 撤下 / 续租）。 */
    private int drawMarketPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        ResidenceSwitches switches = ResidenceCache.INSTANCE.switches();
        y = drawPageTitle(graphics, x, y, "市场出租");
        y = detailLine(graphics, x, y, "当前状态：", entry.marketText());
        if (!switches.economy()) {
            return drawHint(graphics, x, y, "服务器未启用经济系统，市场不可用") + 6;
        }
        if (entry.rented()) {
            y = detailLine(graphics, x, y, "租客：", entry.renter().isBlank() ? "未知" : entry.renter());
            if (!entry.rentExpireText().isBlank()) {
                y = detailLine(graphics, x, y, "到期：", entry.rentExpireText());
            }
            y += 6;
            y = drawHint(graphics, x, y, "领地被租用期间不能出售、不能删除（这是 Residence 的限制）");
            drawButton(graphics, mouseX, mouseY, x, y, 96, BTN_H, "退租（结束）", true, () ->
                    pendingConfirm = new Confirm("结束这块领地的出租",
                            "租客会立刻失去使用权，已付租金按 Residence 的规则处理，无法撤销。",
                            "确认退租", () -> ResUiClient.unrentResidence(entry.name())));
            y += BTN_H + 10;
            drawButton(graphics, mouseX, mouseY, x, y, 96, BTN_H, "手动收租", true,
                    () -> ResUiClient.payRent(entry.name()));
            y += BTN_H + 8;
            drawButton(graphics, mouseX, mouseY, x, y, 120, BTN_H,
                    entry.rentAutoPay() ? "关闭自动续租" : "开启自动续租", true,
                    () -> ResUiClient.setRentAutoPay(entry.name(), !entry.rentAutoPay()));
            y += BTN_H + 8;
            return y + 6;
        }
        // 出售区：出租中的领地不能出售（Residence 限制），这里直接置灰并把原因写在下面
        boolean canSell = !entry.forRent() && !entry.rented();
        y = drawSection(graphics, x, y, "出售");
        y = drawField(graphics, mouseX, mouseY, x, y, "market.price", "售价（整数）");
        int buttonX = x;
        if (entry.forSale()) {
            drawButton(graphics, mouseX, mouseY, buttonX, y, 72, BTN_H, "改价", true, () -> marketSell());
            buttonX += 78;
            drawButton(graphics, mouseX, mouseY, buttonX, y, 84, BTN_H, "撤下出售", true,
                    () -> ResUiClient.unsellResidence(entry.name()));
        } else if (canSell) {
            drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "挂牌出售", true, () -> marketSell());
        } else {
            drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "挂牌出售", true, () ->
                    ResidenceCache.INSTANCE.setFeedback(false,
                            "领地在出租中，Residence 不允许同时出售（先撤下出租）"));
            y = drawHint(graphics, x, y, "⚠ 领地在出租中，不能同时出售（Residence 限制）——先「撤下出租」再挂牌出售");
        }
        y += BTN_H + 12;
        // 出租区
        y = drawSection(graphics, x, y, "出租");
        if (!switches.rentSystem()) {
            y = drawHint(graphics, x, y, "服务器未启用出租系统（config.yml 的 EnableRentSystem）");
        } else {
            y = drawField(graphics, mouseX, mouseY, x, y, "market.cost", "租金 / 期（整数）");
            y = drawField(graphics, mouseX, mouseY, x, y, "market.days", "每期天数（整数）");
            buttonX = x;
            drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "挂牌出租", true, () -> marketRentable());
            buttonX += 102;
            if (entry.forRent()) {
                drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "撤下出租", true,
                        () -> ResUiClient.unrentableResidence(entry.name()));
            }
            y += BTN_H + 8;
            if (entry.forRent()) {
                String price = entry.rentCostText().isBlank() ? entry.rentCost() + "" : entry.rentCostText();
                y = detailLine(graphics, x, y, "当前挂牌：", price + " / " + entry.rentDays() + " 天");
                y = detailLine(graphics, x, y, "可续租：", entry.rentAllowRenewing() ? "允许" : "不允许");
                y = detailLine(graphics, x, y, "到期后留在市场：", entry.rentStayInMarket() ? "是" : "否");
                y = detailLine(graphics, x, y, "允许自动续租：", entry.rentAllowAutoPay() ? "是" : "否");
            }
        }
        y += 8;
        // 租约（另一套系统）
        y = drawSection(graphics, x, y, "租约");
        if (!switches.leaseSystem()) {
            y = drawHint(graphics, x, y, "服务器未启用租约系统（config.yml 的 UseLeaseSystem），此区不可用");
        } else {
            y = detailLine(graphics, x, y, "租约到期：", entry.leaseExpireText().isBlank() ? "无租约" : entry.leaseExpireText());
            y = detailLine(graphics, x, y, "续租费用：", entry.leaseRenewCostText().isBlank() ? "未知" : entry.leaseRenewCostText());
            if (!entry.leaseExpireText().isBlank()) {
                drawButton(graphics, mouseX, mouseY, x, y, 84, BTN_H, "续租", true,
                        () -> ResUiClient.renewLease(entry.name()));
                y += BTN_H + 8;
            }
        }
        return y + 6;
    }

    /** drawSection: 页内小节标题（带一条分隔线）。 */
    private int drawSection(GuiGraphics graphics, int x, int y, String title) {
        graphics.drawString(this.font, title, x, y, COLOR_TEXT, true);
        int lineY = y + 11;
        graphics.fill(x, lineY, x + 200, lineY + 1, COLOR_BORDER);
        return y + 18;
    }

    /** marketSell: 校验售价后挂牌（改价 = 先撤下再挂）。 */
    private void marketSell() {
        ResidenceEntry entry = entry();
        int price;
        try {
            price = Integer.parseInt(field("market.price").trim());
        } catch (NumberFormatException invalid) {
            ResidenceCache.INSTANCE.setFeedback(false, "售价必须是大于 0 的整数");
            return;
        }
        if (price <= 0) {
            ResidenceCache.INSTANCE.setFeedback(false, "售价必须是大于 0 的整数");
            return;
        }
        if (entry.forSale()) {
            // 服务端不允许重复上架，这里先撤下再挂
            ResUiClient.unsellResidence(entry.name());
        }
        ResUiClient.sellResidence(entry.name(), price);
    }

    /** marketRentable: 校验租金与天数后挂牌出租。 */
    private void marketRentable() {
        int cost;
        int days;
        try {
            cost = Integer.parseInt(field("market.cost").trim());
            days = Integer.parseInt(field("market.days").trim());
        } catch (NumberFormatException invalid) {
            ResidenceCache.INSTANCE.setFeedback(false, "租金与天数都必须是大于 0 的整数");
            return;
        }
        if (cost <= 0 || days <= 0) {
            ResidenceCache.INSTANCE.setFeedback(false, "租金与天数都必须是大于 0 的整数");
            return;
        }
        ResUiClient.rentableResidence(entry().name(), cost, days, true, true);
    }

    /** drawChatPage: 领地聊天频道（加入 / 退出 / 前缀颜色 / 踢人）。 */
    private int drawChatPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        ResidenceSwitches switches = ResidenceCache.INSTANCE.switches();
        y = drawPageTitle(graphics, x, y, "领地聊天");
        if (!switches.residenceChat()) {
            return drawHint(graphics, x, y, "服务器未启用领地聊天（config.yml 的 ResidenceChatEnable）") + 6;
        }
        y = detailLine(graphics, x, y, "频道里人数：", entry.chatMembers() + " 人");
        y = detailLine(graphics, x, y, "频道前缀：", entry.chatPrefix().isBlank() ? "（未设置）" : entry.chatPrefix());
        y = detailLine(graphics, x, y, "频道颜色：", entry.chatColor().isBlank() ? "（默认）" : entry.chatColor());
        y += 6;
        // 加入 / 退出
        drawButton(graphics, mouseX, mouseY, x, y, 96, BTN_H, "加入频道", true,
                () -> ResUiClient.chatJoin(entry.name()));
        drawButton(graphics, mouseX, mouseY, x + 102, y, 96, BTN_H, "退出频道", true,
                () -> ResUiClient.chatLeave(entry.name()));
        y += BTN_H + 4;
        y = drawHint(graphics, x, y, "加入后在聊天框直接打字就发到领地频道（服务端提醒：也可以用 /res rc 切换）");
        y += 8;
        // 前缀与颜色
        y = drawField(graphics, mouseX, mouseY, x, y, "chat.prefix", "频道前缀（最长 16 字符）");
        y = drawField(graphics, mouseX, mouseY, x, y, "chat.color", "颜色名，如 RED / GOLD / DARK_PURPLE");
        drawButton(graphics, mouseX, mouseY, x, y, 84, BTN_H, "保存设置", true,
                () -> ResUiClient.setChatSettings(entry.name(), field("chat.prefix"), field("chat.color")));
        y += BTN_H + 10;
        // 踢人
        y = drawField(graphics, mouseX, mouseY, x, y, "chat.kick", "要移出领地的玩家名");
        drawButton(graphics, mouseX, mouseY, x, y, 96, BTN_H, "移出领地", true, () -> {
            String target = field("chat.kick").trim();
            if (target.isEmpty()) {
                ResidenceCache.INSTANCE.setFeedback(false, "请先填写玩家名");
                return;
            }
            pendingConfirm = new Confirm("把 " + target + " 移出领地",
                    "他会被传送到领地外的安全点，权限不变（下次还能进来）。", "确认移出",
                    () -> ResUiClient.kickFromResidence(entry.name(), target));
        });
        y += BTN_H + 8;
        y = drawHint(graphics, x, y, "频道成员名单由 Residence 内部维护，插件拿不到完整列表（只能看到人数）");
        return y + 6;
    }

    /** drawAreasPage: 区域（cuboid）列表 + 子领地列表。 */
    private int drawAreasPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        y = drawPageTitle(graphics, x, y, "区域与子领地");
        y = drawHint(graphics, x, y, "区域 = 同一块领地里的多个长方体；用选区工具（/res tool 或木锄）点两个对角后可增删改");
        // 管理动作
        y = drawField(graphics, mouseX, mouseY, x, y, "area.id", "区域名（如 main、farm）");
        int buttonX = x;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "用选区添加", true,
                () -> ResUiClient.areaAction(entry.name(), "add", field("area.id").trim(), null));
        buttonX += 102;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "用选区替换", true,
                () -> ResUiClient.areaAction(entry.name(), "replace", field("area.id").trim(), null));
        buttonX += 102;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 84, BTN_H, "设为主区域", true,
                () -> ResUiClient.areaAction(entry.name(), "setmain", field("area.id").trim(), null));
        y += BTN_H + 8;
        // 区域列表
        List<ResidenceEntry.AreaRef> areas = entry.areaList();
        y = drawHint(graphics, x, y, "区域列表（" + areas.size() + "）");
        if (areas.isEmpty()) {
            y = drawHint(graphics, x, y, "（服务端未下发区域明细，或这块领地的数据来自旧版插件）") + 4;
        }
        int index = 0;
        for (ResidenceEntry.AreaRef area : areas) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            String label = (area.main() ? "★ " : "") + area.id();
            graphics.drawString(this.font, label, x + 6, textY, COLOR_TEXT, true);
            String detail = area.rangeText() + "  " + area.sizeText();
            graphics.drawString(this.font, trimToWidth(detail, right - x - 260),
                    x + 88, textY, COLOR_MUTED, true);
            int cursor = right - 6 - 52;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "删除", true, () ->
                    ResUiClient.areaAction(entry.name(), "remove", area.id(), null));
            cursor -= 58;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "传送", true, () ->
                    ResUiClient.teleportToArea(entry.name(), area.id()));
            index++;
            y += ROW_H + 2;
        }
        y += 8;
        // 子领地
        List<ResidenceEntry.SubzoneRef> subzones = entry.subzones();
        y = drawHint(graphics, x, y, "子领地（" + subzones.size() + "）—— 子领地是独立的保护区域，可单独设权限与出租");
        if (entry.subzone()) {
            y = drawHint(graphics, x, y, "注意：当前这块领地自己就是子领地（父领地：" + entry.parent() + "）");
        }
        index = 0;
        for (ResidenceEntry.SubzoneRef subzone : subzones) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            graphics.drawString(this.font, subzone.name(), x + 6, textY, COLOR_TEXT, true);
            String detail = subzone.owner() + " · " + subzone.areas() + " 区域 · " + subzone.size() + "m³"
                    + (subzone.rented() ? " · 已出租" : subzone.forRent() ? " · 出租中" : "");
            graphics.drawString(this.font, trimToWidth(detail, right - x - 160),
                    x + 130, textY, COLOR_MUTED, true);
            drawButton(graphics, mouseX, mouseY, right - 6 - 72, y + 2, 72, BTN_H, "删子领地", true, () -> {
                pendingConfirm = new Confirm("删除子领地 " + subzone.name(),
                        "子领地里的保护设置会一起消失，且无法撤销。", "确认删除",
                        () -> ResUiClient.subzoneAction(subzone.name(), "remove", null));
            });
            index++;
            y += ROW_H + 2;
        }
        y += 8;
        y = drawField(graphics, mouseX, mouseY, x, y, "sub.name", "新子领地名字");
        drawButton(graphics, mouseX, mouseY, x, y, 120, BTN_H, "用选区创建子领地", true,
                () -> ResUiClient.subzoneAction(entry.name(), "create", field("sub.name").trim()));
        y += BTN_H + 10;
        return y;
    }

    /** drawMePage: 「我的」页 —— 我的领地 / 租用中的领地 + 常用入口。 */
    private int drawMePage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceCache cache = ResidenceCache.INSTANCE;
        ResidenceMe me = cache.me();
        ResidenceMe.Limits limits = me.limits();
        y = drawPageTitle(graphics, x, y, "我的");
        y = detailLine(graphics, x, y, "玩家：", me.name().isBlank() ? "未知" : me.name());
        y = detailLine(graphics, x, y, "当前所在：", me.currentText());
        y = detailLine(graphics, x, y, "权限组：", limits.group().isBlank() ? "未知" : limits.group());
        y = detailLine(graphics, x, y, "我的领地：", cache.own().size() + " 块"
                + (limits.maxResidences() < 0 ? "（无上限）" : "（上限 " + limits.maxResidences() + "）"));
        y = detailLine(graphics, x, y, "租用中的领地：", cache.rented().size() + " 块"
                + (limits.maxRents() < 0 ? "" : "（上限 " + limits.maxRents() + "）"));
        y = detailLine(graphics, x, y, "还能新建：", limits.maxResidences() < 0 ? "无限制"
                : String.valueOf(Math.max(0, limits.maxResidences() - limits.usedResidences())));
        y = detailLine(graphics, x, y, "我的余额：", me.money().isBlank() ? "经济未启用" : me.money());
        if (me.admin()) {
            y = detailLine(graphics, x, y, "身份：", "管理员（residence.admin）");
        }
        y += 6;
        int buttonX = x;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 84, BTN_H, "刷新数据", false,
                ResUiClient::requestRefresh);
        buttonX += 90;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 84, BTN_H, "刷新上限", false,
                ResUiClient::refreshMe);
        buttonX += 90;
        drawButton(graphics, mouseX, mouseY, buttonX, y, 96, BTN_H, "查看边界", false,
                () -> ResUiClient.sendCommand("res show " + entry().name()));
        y += BTN_H + 12;
        // 我的领地：只读展示，点「管理」进管理面板
        y = drawSection(graphics, x, y, "我的领地（" + cache.own().size() + "）");
        if (cache.own().isEmpty()) {
            y = drawHint(graphics, x, y, "还没有领地：先用选区工具圈地，再执行 /res create 领地名");
        }
        int index = 0;
        for (ResidenceEntry own : cache.own()) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            graphics.drawString(this.font, own.name(), x + 6, textY, COLOR_TEXT, true);
            String detail = own.sizeText() + " · " + own.areas() + " 区域"
                    + (own.subzone() ? " · 子领地" : "")
                    + (ResUiConfig.INSTANCE.showMarketInList() && (own.forSale() || own.forRent() || own.rented())
                    ? " · " + own.marketText() : "");
            graphics.drawString(this.font, trimToWidth(detail, right - x - 140),
                    x + 130, textY, COLOR_MUTED, true);
            drawButton(graphics, mouseX, mouseY, right - 6 - 52, y + 2, 52, BTN_H, "管理", true,
                    () -> Minecraft.getInstance().setScreen(new ResidenceManageScreen(own)));
            index++;
            y += ROW_H + 2;
        }
        y += 8;
        y = drawSection(graphics, x, y, "租用中的领地（" + cache.rented().size() + "）");
        if (cache.rented().isEmpty()) {
            y = drawHint(graphics, x, y, "没有租用中的领地：在「公共领地」页签里可以租别人的地");
        }
        index = 0;
        for (ResidenceEntry rented : cache.rented()) {
            int textY = drawRow(graphics, x, y, right, index, ROW_H);
            graphics.drawString(this.font, rented.name(), x + 6, textY, COLOR_TEXT, true);
            String detail = rented.marketText()
                    + (rented.rentExpireText().isBlank() ? "" : " · " + rented.rentExpireText());
            graphics.drawString(this.font, trimToWidth(detail, right - x - 200),
                    x + 130, textY, COLOR_MUTED, true);
            String name = rented.name();
            int cursor = right - 6 - 52;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "续租", true,
                    () -> ResUiClient.payRent(name));
            cursor -= 58;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "退租", true, () ->
                    pendingConfirm = new Confirm("退租 " + name,
                            "退租后你会立刻失去使用权，已付租金按 Residence 规则不退还。", "确认退租",
                            () -> ResUiClient.unrentResidence(name)));
            index++;
            y += ROW_H + 2;
        }
        return y + 6;
    }

    /** drawSettingsPage: 「设置」页（内容与领地列表页右上角头像里的设置完全共用一份实现）。 */
    private int drawSettingsPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        return ResUiSettingsPanel.draw(this, graphics, mouseX, mouseY, x, y, right,
                contentTop, contentBottom, settingsInteraction(), settingsInput());
    }

    /** settingsInteraction: 设置页的控件（内容视口判定由 ResUiSettingsPanel 内部做）。 */
    private ResUiSettingsPanel.Interaction settingsInteraction() {
        return new ResUiSettingsPanel.Interaction() {
            @Override
            public void clickable(int x1, int y1, int x2, int y2, Runnable action) {
                clickables.add(new Clickable(x1, y1, x2, y2, true, false, action));
            }

            @Override
            public boolean isInside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
                return ResidenceManageScreen.isInside(mouseX, mouseY, x1, y1, x2, y2);
            }
        };
    }

    /** settingsInput: 设置页现在没有输入框（背景图用按钮选），这里只留空实现。 */
    private ResUiSettingsPanel.Input settingsInput() {
        return new ResUiSettingsPanel.Input() {
            @Override
            public String value(String key) {
                return "";
            }

            @Override
            public void setValue(String key, String value) {
                // 设置页已无输入框
            }

            @Override
            public String focused() {
                return null;
            }

            @Override
            public void focus(String key) {
                // 设置页已无输入框
            }
        };
    }

    /** drawFlagsPage: 领地权限列表（全部权限，可搜索）。 */
    private int drawFlagsPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceEntry entry = entry();
        List<FlagOption> flags = filteredFlags();
        if (flags.isEmpty()) {
            graphics.drawString(this.font, "没有匹配的权限", x, y + 6, COLOR_MUTED, true);
            return y + ROW_H;
        }
        int index = 0;
        for (FlagOption flag : flags) {
            graphics.fill(x, y, right, y + FLAG_ROW_H, index % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT);
            drawBorder(graphics, x, y, right, y + FLAG_ROW_H);
            drawFlagLabel(graphics, x + 6, y + 7, flag);
            boolean on = entry.flag(flag.name());
            String flagName = flag.name();
            drawToggle(graphics, mouseX, mouseY, right - 6 - TOGGLE_W, y + 2, on,
                    () -> changeFlag(flagName, !entry.flag(flagName)));
            index++;
            y += FLAG_ROW_H + 2;
        }
        return y;
    }

    /** drawFlagLabel: 一行权限名：中文说明 + 英文 flag 名。 */
    private void drawFlagLabel(GuiGraphics graphics, int x, int y, FlagOption flag) {
        String label = flag.display();
        graphics.drawString(this.font, label, x, y, COLOR_TEXT, true);
        graphics.drawString(this.font, flag.name(), x + this.font.width(label) + 8, y, COLOR_MUTED, true);
    }

    /** filteredFlags: 服务端推来的全部权限项，按搜索关键词过滤（中文说明或英文名都能匹配）。 */
    private List<FlagOption> filteredFlags() {
        String keyword = flagFilter.trim().toLowerCase(Locale.ROOT);
        List<FlagOption> result = new ArrayList<>();
        for (FlagOption flag : ResidenceCache.INSTANCE.allFlags()) {
            if (keyword.isEmpty()
                    || flag.name().toLowerCase(Locale.ROOT).contains(keyword)
                    || flag.display().toLowerCase(Locale.ROOT).contains(keyword)) {
                result.add(flag);
            }
        }
        return result;
    }

    private void changeFlag(String flag, boolean value) {
        ResUiClient.setFlag(entry().name(), flag, value);
    }

    /** drawMembersToolbar: 成员页顶部工具条，返回列表起始 y。 */
    private int drawMembersToolbar(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                   int x, int y, int right) {
        drawButton(graphics, mouseX, mouseY, x, y, 84, BTN_H, "添加成员", false, () -> {
            pickingPlayer = true;
            scroll = 0;
            playerFilter = "";
        });
        graphics.drawString(this.font, "点右侧“权限”可单独设置该玩家的权限",
                x + 92, y + 5, COLOR_MUTED, true);
        return y + BTN_H + 8;
    }

    /** drawPickerHeader: 选择在线玩家页的搜索栏，返回列表起始 y。 */
    private int drawPickerHeader(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                 int x, int y, int right) {
        drawButton(graphics, mouseX, mouseY, x, y, 56, BTN_H, "< 返回", false, () -> {
            pickingPlayer = false;
            scroll = 0;
        });
        int boxX = x + 64;
        drawSearchBox(graphics, boxX, y + 3, PLAYER_SEARCH_W, playerFilter, "搜索玩家");
        graphics.drawString(this.font, "选择在线玩家",
                boxX + PLAYER_SEARCH_W + 10, y + 5, COLOR_MUTED, true);
        return y + BTN_H + 8;
    }

    /** drawSearchBox: 自绘的紧凑搜索框（比原版 EditBox 小，样式统一）。 */
    private void drawSearchBox(GuiGraphics graphics, int x, int y, int width, String text, String hint) {
        graphics.fill(x, y, x + width, y + SEARCH_H, 0xFF1A1A1A);
        drawBorder(graphics, x, y, x + width, y + SEARCH_H);
        boolean empty = text == null || text.isEmpty();
        String shown = empty ? hint : text;
        int color = empty ? COLOR_MUTED : COLOR_TEXT;
        int textY = y + (SEARCH_H - 8) / 2;
        graphics.drawString(this.font, shown, x + 4, textY, color, true);
        if (!empty) {
            int cursorX = x + 4 + this.font.width(text);
            graphics.fill(cursorX + 1, y + 3, cursorX + 2, y + SEARCH_H - 3, 0xFFDDDDDD);
        }
    }

    /** drawOnlinePlayers: 在线玩家列表（按搜索词过滤），点“添加”把该玩家加为成员。 */
    private int drawOnlinePlayers(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        List<String> names = onlinePlayerNames();
        if (names.isEmpty()) {
            graphics.drawString(this.font, "没有匹配的在线玩家", x, y + 6, COLOR_MUTED, true);
            return y + ROW_H;
        }
        int index = 0;
        for (String name : names) {
            graphics.fill(x, y, right, y + ROW_H, index % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT);
            drawBorder(graphics, x, y, right, y + ROW_H);
            graphics.drawString(this.font, name, x + 6, y + 7, COLOR_TEXT, true);
            if (entry().member(name) != null) {
                graphics.drawString(this.font, "已是成员",
                        x + 6 + this.font.width(name) + 10, y + 7, COLOR_MUTED, true);
            }
            drawButton(graphics, mouseX, mouseY, right - 6 - 52, y + 2, 52, BTN_H, "添加", true,
                    () -> addMemberFromPicker(name));
            index++;
            y += ROW_H + 2;
        }
        return y;
    }

    /** onlinePlayerNames: 当前在线玩家名（已按搜索框内容过滤）。 */
    private List<String> onlinePlayerNames() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return List.of();
        }
        String keyword = playerFilter.trim().toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!keyword.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** drawMembersList: 成员列表（玩家名 + 权限数 + 权限/移除按钮）。 */
    private int drawMembersList(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        List<ResidenceMember> members = entry().players();
        if (members.isEmpty()) {
            graphics.drawString(this.font, "暂无成员：点左上角“添加成员”把玩家加进来",
                    x, y + 6, COLOR_MUTED, true);
            return y + ROW_H;
        }
        int index = 0;
        for (ResidenceMember member : members) {
            graphics.fill(x, y, right, y + ROW_H, index % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT);
            drawBorder(graphics, x, y, right, y + ROW_H);
            graphics.drawString(this.font, member.name(), x + 6, y + 7, COLOR_TEXT, true);
            graphics.drawString(this.font, "已单独设置 " + member.flagCount() + " 项权限",
                    x + 6 + this.font.width(member.name()) + 10, y + 7, COLOR_MUTED, true);
            String playerName = member.name();
            int cursor = right - 6 - 52;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "权限", true, () -> {
                selectedMember = playerName;
                scroll = 0;
            });
            cursor -= 58;
            drawButton(graphics, mouseX, mouseY, cursor, y + 2, 52, BTN_H, "移除", true,
                    () -> removeMember(playerName));
            index++;
            y += ROW_H + 2;
        }
        return y;
    }

    /** drawMemberHeader: 成员权限页顶部返回栏，返回列表起始 y。 */
    private int drawMemberHeader(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        drawButton(graphics, mouseX, mouseY, x, y, 56, BTN_H, "< 返回", false, () -> {
            selectedMember = null;
            scroll = 0;
            flagFilter = "";
        });
        graphics.drawString(this.font, selectedMember + " 的权限（默认 = 继承领地权限）",
                x + 64, y + 5, COLOR_MUTED, true);
        return y + BTN_H + 8;
    }

    /** drawMemberFlags: 某成员的权限列表（全部权限，可搜索，三态：默认 / 允许 / 禁止）。 */
    private int drawMemberFlags(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
        ResidenceMember member = entry().member(selectedMember);
        if (member == null) {
            graphics.drawString(this.font, "该成员已不存在，点“< 返回”回到列表", x, y + 6, COLOR_MUTED, true);
            return y + ROW_H;
        }
        List<FlagOption> flags = filteredFlags();
        if (flags.isEmpty()) {
            graphics.drawString(this.font, "没有匹配的权限", x, y + 6, COLOR_MUTED, true);
            return y + ROW_H;
        }
        int index = 0;
        for (FlagOption flag : flags) {
            graphics.fill(x, y, right, y + FLAG_ROW_H, index % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT);
            drawBorder(graphics, x, y, right, y + FLAG_ROW_H);
            drawFlagLabel(graphics, x + 6, y + 7, flag);
            Boolean state = member.flag(flag.name());
            String flagName = flag.name();
            drawTriToggle(graphics, mouseX, mouseY, right - 6 - TRI_TOGGLE_W, y + 2, state,
                    () -> cycleMemberFlag(flagName, state));
            index++;
            y += FLAG_ROW_H + 2;
        }
        return y;
    }

    /** cycleMemberFlag: 三态循环：默认 → 允许 → 禁止 → 默认。 */
    private void cycleMemberFlag(String flag, Boolean current) {
        String next;
        if (current == null) {
            next = "true";
        } else if (current) {
            next = "false";
        } else {
            next = "remove";
        }
        ResUiClient.setMemberFlag(entry().name(), selectedMember, flag, next);
    }

    /** addMemberFromPicker: 把在线玩家列表里选中的玩家加为成员，然后回到成员列表。 */
    private void addMemberFromPicker(String player) {
        ResUiClient.addMember(entry().name(), player);
        pickingPlayer = false;
        scroll = 0;
    }

    private void removeMember(String player) {
        ResUiClient.removeMember(entry().name(), player);
    }

    private int detailLine(GuiGraphics graphics, int x, int y, String label, String value) {
        graphics.drawString(this.font, label, x, y, COLOR_MUTED, true);
        graphics.drawString(this.font, value, x + this.font.width(label), y, COLOR_TEXT, true);
        return y + 12;
    }

    private void drawNavButton(GuiGraphics graphics, int mouseX, int mouseY,
                               int x, int y, int width, int height, String label, int pageIndex) {
        boolean active = page == pageIndex;
        boolean hover = isInside(mouseX, mouseY, x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + height,
                active ? COLOR_TAB_ACTIVE : (hover ? COLOR_BTN_HOVER : COLOR_TAB));
        drawBorder(graphics, x, y, x + width, y + height);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (width - textWidth) / 2, y + (height - 8) / 2 + 1,
                active ? COLOR_TEXT : COLOR_MUTED, true);
        clickables.add(new Clickable(x, y, x + width, y + height, true, false, () -> setPage(pageIndex)));
    }

    private void drawButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width, int height,
                            String label, boolean inContent, Runnable action) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + height, hover ? COLOR_BTN_HOVER : COLOR_BTN);
        drawBorder(graphics, x, y, x + width, y + height);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (width - textWidth) / 2, y + (height - 8) / 2, COLOR_TEXT, true);
        clickables.add(new Clickable(x, y, x + width, y + height, true, inContent, action));
    }

    /** drawToggle: 权限开关（开=绿，关=红）。 */
    private void drawToggle(GuiGraphics graphics, int mouseX, int mouseY,
                            int x, int y, boolean on, Runnable action) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + TOGGLE_W, y + TOGGLE_H);
        int background;
        if (on) {
            background = hover ? COLOR_ON_HOVER : COLOR_ON;
        } else {
            background = hover ? COLOR_OFF_HOVER : COLOR_OFF;
        }
        graphics.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, background);
        drawBorder(graphics, x, y, x + TOGGLE_W, y + TOGGLE_H);
        String label = on ? "允许" : "禁止";
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (TOGGLE_W - textWidth) / 2, y + (TOGGLE_H - 8) / 2,
                COLOR_TEXT, true);
        boolean visible = y + TOGGLE_H > contentTop && y < contentBottom;
        clickables.add(new Clickable(x, y, x + TOGGLE_W, y + TOGGLE_H, true, visible, action));
    }

    /** drawTriToggle: 成员权限开关（三态：默认=灰 / 允许=绿 / 禁止=红）。 */
    private void drawTriToggle(GuiGraphics graphics, int mouseX, int mouseY,
                               int x, int y, Boolean state, Runnable action) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + TRI_TOGGLE_W, y + TOGGLE_H);
        int background;
        String label;
        if (state == null) {
            background = hover ? COLOR_DEFAULT_HOVER : COLOR_DEFAULT;
            label = "默认";
        } else if (state) {
            background = hover ? COLOR_ON_HOVER : COLOR_ON;
            label = "允许";
        } else {
            background = hover ? COLOR_OFF_HOVER : COLOR_OFF;
            label = "禁止";
        }
        graphics.fill(x, y, x + TRI_TOGGLE_W, y + TOGGLE_H, background);
        drawBorder(graphics, x, y, x + TRI_TOGGLE_W, y + TOGGLE_H);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (TRI_TOGGLE_W - textWidth) / 2, y + (TOGGLE_H - 8) / 2,
                COLOR_TEXT, true);
        boolean visible = y + TOGGLE_H > contentTop && y < contentBottom;
        clickables.add(new Clickable(x, y, x + TRI_TOGGLE_W, y + TOGGLE_H, true, visible, action));
    }

    private void drawCloseButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        boolean hover = isInside(mouseX, mouseY, x, y, x + CLOSE_SIZE, y + CLOSE_SIZE);
        graphics.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, hover ? COLOR_BTN_HOVER : COLOR_BTN);
        drawBorder(graphics, x, y, x + CLOSE_SIZE, y + CLOSE_SIZE);
        graphics.drawString(this.font, "x", x + 6, y + 5, COLOR_TEXT, true);
        clickables.add(new Clickable(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, true, false, this::onClose));
    }

    private void drawScrollBar(GuiGraphics graphics, int x) {
        int viewHeight = contentBottom - contentTop;
        scrollBarVisible = contentHeight > viewHeight;
        if (!scrollBarVisible) {
            return;
        }
        graphics.fill(x, contentTop, x + 3, contentBottom, 0x40000000);
        int knobHeight = Math.max(16, viewHeight * viewHeight / contentHeight);
        int maxScroll = contentHeight - viewHeight;
        int knobY = contentTop + (int) ((viewHeight - knobHeight) * (scroll / maxScroll));
        graphics.fill(x, knobY, x + 3, knobY + knobHeight, 0xFF9A9A9A);
        // 记录几何信息，供鼠标拖拽使用
        this.scrollBarX = x;
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

    /** flagSearchActive: 当前是否在权限列表页（此时键盘输入用于过滤权限）。 */
    private boolean flagSearchActive() {
        return page == PAGE_FLAGS || (page == PAGE_MEMBERS && selectedMember != null);
    }

    /**
     * textInputActive: 当前页面是否有输入框在接收键盘（输入框页要用它拦住按键，
     * 否则打字会被原版当成快捷键）。
     */
    private boolean textInputActive() {
        return page == PAGE_MESSAGES || page == PAGE_LISTS || page == PAGE_COMMANDS
                || page == PAGE_SETTINGS || isTyping();
    }

    /** setPage: 切页时统一清理输入焦点与滚动位置（避免上一页的输入串到新页）。 */
    private void setPage(int newPage) {
        page = newPage;
        scroll = 0;
        selectedMember = null;
        pickingPlayer = false;
        flagFilter = "";
        playerFilter = "";
        focusedField = null;
        fieldValues.clear();
        prepareFields();
    }

    /** prepareFields: 用服务端推来的现有值预填输入框（只在为空时填，不覆盖正在输入的内容）。 */
    private void prepareFields() {
        ResidenceEntry entry = entry();
        if (page == PAGE_MESSAGES) {
            if (field("msg.enter").isEmpty()) {
                setField("msg.enter", entry.enterMessage());
            }
            if (field("msg.leave").isEmpty()) {
                setField("msg.leave", entry.leaveMessage());
            }
        }
    }

    /** keyPressed: 搜索状态下自行处理退格，其余按键不冒泡给原版（Esc 例外）。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pickingPlayer) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                pickingPlayer = false;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                playerFilter = trimLast(playerFilter);
            }
            return true;
        }
        if (textInputActive()) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                // Esc：先退出输入焦点，再按一次才关界面
                if (isTyping()) {
                    focusedField = null;
                    return true;
                }
                return super.keyPressed(event);
            }
            if (isTyping() && event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                setField(focusedField, trimLast(field(focusedField)));
            }
            return true;
        }
        if (flagSearchActive() && event.key() != GLFW.GLFW_KEY_ESCAPE) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                flagFilter = trimLast(flagFilter);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter()) {
            return super.charTyped(event);
        }
        String typed = event.codepointAsString();
        if (pickingPlayer) {
            playerFilter = append(playerFilter, typed, 16);
            return true;
        }
        if (isTyping()) {
            setField(focusedField, append(field(focusedField), typed, MAX_FIELD_LENGTH));
            return true;
        }
        if (flagSearchActive()) {
            flagFilter = append(flagFilter, typed, 20);
            return true;
        }
        return super.charTyped(event);
    }

    private static String trimLast(String text) {
        return text.isEmpty() ? text : text.substring(0, text.length() - 1);
    }

    private static String append(String text, String typed, int maxLength) {
        return text.length() + typed.length() > maxLength ? text : text + typed;
    }

    private record Clickable(int x1, int y1, int x2, int y2, boolean enabled, boolean inContent, Runnable action) {
    }
}
