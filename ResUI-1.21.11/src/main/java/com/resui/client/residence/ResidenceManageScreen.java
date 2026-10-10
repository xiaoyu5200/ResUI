package com.resui.client.residence;

import com.resui.client.ResUiClient;
import com.resui.client.bridge.FlagOption;
import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceEntry;
import com.resui.client.bridge.ResidenceMember;
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
    /** 界面背景图（与领地列表页共用同一张）。 */
    private static final Identifier BACKGROUND =
            Identifier.fromNamespaceAndPath("resui", "textures/gui/background.png");
    private static final int BACKGROUND_W = 1280;
    private static final int BACKGROUND_H = 720;

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

    public ResidenceManageScreen(ResidenceEntry entry) {
        super(Component.literal("领地管理"));
        this.fallbackEntry = entry;
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
        int navY = bodyY + 6;
        drawNavButton(graphics, mouseX, mouseY, sidebarX + 6, navY, SIDEBAR_W - 12, NAV_H, "领地信息", 0);
        drawNavButton(graphics, mouseX, mouseY, sidebarX + 6, navY + NAV_H + 4, SIDEBAR_W - 12, NAV_H, "权限管理", 1);
        drawNavButton(graphics, mouseX, mouseY, sidebarX + 6, navY + (NAV_H + 4) * 2, SIDEBAR_W - 12, NAV_H, "领地成员", 2);

        // 右侧内容
        int contentX = sidebarX + SIDEBAR_W + GAP;
        int contentRight = right - GAP;
        this.contentTop = bodyY;
        this.contentBottom = bodyBottom;
        if (page == 0) {
            drawInfoPage(graphics, mouseX, mouseY, contentX + 8, bodyY + 8, contentRight - 8);
        } else if (page == 2 && pickingPlayer) {
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
        } else if (page == 2 && selectedMember == null) {
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
        } else if (page == 2) {
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
        } else {
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
        }
        drawFeedback(graphics);
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
                ResidenceCache.INSTANCE.feedbackOk() ? COLOR_FEEDBACK_OK : COLOR_FEEDBACK_FAIL, true);
    }

    /** drawInfoPage: 领地信息页（基本信息 + 公开/私有开关）。 */
    private void drawInfoPage(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int right) {
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
        detailLine(graphics, x, lineY,
                "可传送：", entry.flag("tp") ? "是（别人可 /res tp 到你的领地）" : "否（仅自己可传送）");

        int buttonY = contentBottom - 30;
        int buttonW = 88;
        int gap = 8;
        int startX = x + ((right - x) - (buttonW * 3 + gap * 2)) / 2;
        drawButton(graphics, mouseX, mouseY, startX, buttonY, buttonW, BTN_H, "设为公开", false,
                () -> ResUiClient.setTeleportable(entry().name(), true));
        drawButton(graphics, mouseX, mouseY, startX + buttonW + gap, buttonY, buttonW, BTN_H, "设为私有", false,
                () -> ResUiClient.setTeleportable(entry().name(), false));
        drawButton(graphics, mouseX, mouseY, startX + (buttonW + gap) * 2, buttonY, buttonW, BTN_H, "设置传送点", false,
                () -> ResUiClient.setTeleportPoint(entry().name()));
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
        clickables.add(new Clickable(x, y, x + width, y + height, true, false, () -> {
            page = pageIndex;
            scroll = 0;
            selectedMember = null;
            pickingPlayer = false;
            flagFilter = "";
        }));
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
        return page == 1 || (page == 2 && selectedMember != null);
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
