package com.resui.client.residence;

import com.resui.client.ResUiClient;
import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceEntry;
import com.resui.client.config.ResUiBackgrounds;
import com.resui.client.config.ResUiConfig;
import com.resui.client.config.ResUiSettingsPanel;
import com.resui.client.ui.PlayerHeads;
import com.resui.client.ui.ResUiMarketPanel;
import com.resui.client.ui.ResUiOverlayPanel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * ResidenceListScreen: 展示服务端（Residence 插件）推来的领地数据。
 * 两个分类：自己的领地 / 公共可传送领地；带传送与详细按钮，右上角是玩家头像菜单。
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
    /** 左侧竖排导航的选中底色（比顶部标签略深，配左侧高亮竖条）。 */
    private static final int COLOR_NAV_ACTIVE = 0xFF4A4A4A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;
    private static final int COLOR_ERROR = 0xFFFF7777;
    private static final int COLOR_OK = 0xFF6EE06E;
    /** 二次确认弹窗用的红色。 */
    private static final int COLOR_DANGER = 0xFFB03030;
    /** 界面背景图：实际用哪张由 ResUiBackgrounds 决定（内置图或玩家自定义图）。 */

    private static final int PAD = 8;
    private static final int TITLE_BAR_HEIGHT = 24;
    private static final int GAP = 5;
    /** 左侧竖排导航：宽度、单个按钮高度、底部头像边长。 */
    private static final int SIDE_NAV_WIDTH = 112;
    private static final int NAV_H = 22;
    private static final int AVATAR_SIZE = 24;
    private static final int ROW_H = 26;
    private static final int BTN_H = 16;
    private static final int CLOSE_SIZE = 18;

    private final List<Clickable> clickables = new ArrayList<>();
    private ResidenceEntry detailEntry;
    /** 待确认的花钱 / 丢权限操作（非 null 时渲染确认弹窗并屏蔽底层交互）。 */
    private Confirm pendingConfirm;
    /** 右上角玩家头像的下拉菜单是否展开。 */
    private boolean avatarMenuOpen;
    /** 当前打开的覆盖层（没有就是 NONE）。 */
    private Overlay overlay = Overlay.NONE;
    /** 覆盖层的滚动与输入焦点。 */
    private double overlayScroll;
    /** 设置覆盖层：目前只用于在打开时记录一下当前背景名（已不再需要输入框）。 */
    private String overlayField = "";
    /** 商城覆盖层：筛选条件与当前聚焦的输入框 key。 */
    private final ResUiMarketPanel.Filter marketFilter = new ResUiMarketPanel.Filter();
    private String marketFieldFocus;
    /** 设置覆盖层里当前聚焦的输入框 key。 */
    private String settingsFocusKey;

    /** Overlay: 列表页上的覆盖层类型。 */
    private enum Overlay {
        NONE,
        SETTINGS,
        MARKET
    }

    /** Confirm: 二次确认弹窗的内容与确认后要执行的动作。 */
    private record Confirm(String title, String detail, String confirmLabel, Runnable action) {
    }

    /** 头像菜单里的一项。 */
    private record MenuItem(String label, Runnable action) {
    }
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
        drawSideNav(graphics, mouseX, mouseY, left + GAP, tabsTop, bottom - GAP);
        // 头像放在左侧导航栏底部
        drawAvatarButton(graphics, mouseX, mouseY, left + GAP, bottom - GAP - AVATAR_SIZE);

        int listLeft = left + GAP + SIDE_NAV_WIDTH + GAP;
        int listRight = right - GAP;
        this.contentTop = tabsTop;
        this.contentBottom = bottom - GAP;
        graphics.enableScissor(listLeft, contentTop, listRight, contentBottom);
        int endY = drawList(graphics, mouseX, mouseY, listLeft, contentTop - (int) scroll, listRight);
        graphics.disableScissor();
        this.contentHeight = endY - contentTop + (int) scroll;
        drawScrollBar(graphics, listRight);

        if (detailEntry != null) {
            // 弹窗打开时屏蔽列表交互，只保留弹窗内的按钮
            clickables.clear();
            drawDetailOverlay(graphics, mouseX, mouseY);
        }
        if (pendingConfirm != null) {
            // 二次确认弹窗在最上层：清掉下层控件，避免点到弹窗后面的按钮
            clickables.clear();
            drawConfirm(graphics, mouseX, mouseY);
        }
        if (overlay != Overlay.NONE) {
            // 覆盖层（设置 / 领地商城）：整层独立，先清掉下层控件
            clickables.clear();
            drawOverlay(graphics, mouseX, mouseY);
        } else if (avatarMenuOpen && detailEntry == null && pendingConfirm == null) {
            drawAvatarMenu(graphics, mouseX, mouseY);
        }
        drawFeedback(graphics);
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
                ResidenceCache.INSTANCE.feedbackOk() ? COLOR_OK : COLOR_ERROR, true);
    }

    /**
     * drawSideNav: 左侧竖排导航（我的领地 / 租用中 / 公共领地 / 领地商城）。
     * 竖排之后列表区不再被顶部标签挤掉一行，横向也空出整块给列表。
     */
    private void drawSideNav(GuiGraphics graphics, int mouseX, int mouseY, int x, int top, int bottom) {
        ResidenceCache cache = ResidenceCache.INSTANCE;
        int y = top;
        y = drawNavItem(graphics, mouseX, mouseY, x, y, "我的领地", cache.own().size(),
                overlay == Overlay.NONE && tab == 0, () -> {
                    tab = 0;
                    scroll = 0;
                });
        y = drawNavItem(graphics, mouseX, mouseY, x, y, "租用中", cache.rented().size(),
                overlay == Overlay.NONE && tab == 1, () -> {
                    tab = 1;
                    scroll = 0;
                });
        y = drawNavItem(graphics, mouseX, mouseY, x, y, "公共领地", cache.publicList().size(),
                overlay == Overlay.NONE && tab == 2, () -> {
                    tab = 2;
                    scroll = 0;
                });
        // 领地商城也放在这里（原来在头像菜单里）
        drawNavItem(graphics, mouseX, mouseY, x, y, "领地商城", cache.market().size(),
                overlay == Overlay.MARKET, () -> {
                    // 打开商城时顺手要一份最新数据（挂牌会变）
                    ResUiClient.requestRefresh();
                    openOverlay(Overlay.MARKET);
                });
    }

    /** drawNavItem: 一个竖排导航项，返回下一项的 y。 */
    private int drawNavItem(GuiGraphics graphics, int mouseX, int mouseY, int x, int y,
                            String label, int count, boolean selected, Runnable action) {
        int width = SIDE_NAV_WIDTH;
        boolean hover = isInside(mouseX, mouseY, x, y, x + width, y + NAV_H);
        graphics.fill(x, y, x + width, y + NAV_H,
                selected ? COLOR_NAV_ACTIVE : hover ? COLOR_BTN_HOVER : COLOR_BTN);
        drawBorder(graphics, x, y, x + width, y + NAV_H);
        // 选中态左侧加一条高亮，竖排导航里比只换底色更醒目
        if (selected) {
            graphics.fill(x, y, x + 3, y + NAV_H, COLOR_TEXT);
        }
        graphics.drawString(this.font, label, x + 8, y + (NAV_H - 8) / 2 + 1,
                selected ? COLOR_TEXT : COLOR_MUTED, true);
        String badge = String.valueOf(count);
        graphics.drawString(this.font, badge, x + width - 8 - this.font.width(badge),
                y + (NAV_H - 8) / 2 + 1, selected ? COLOR_TEXT : COLOR_MUTED, true);
        clickables.add(new Clickable(x, y, x + width, y + NAV_H, true, false, action));
        return y + NAV_H + 4;
    }

    /** drawAvatarButton: 左侧导航栏底部的玩家头像（点它展开菜单）。 */
    private void drawAvatarButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y) {
        int size = AVATAR_SIZE;
        boolean hover = isInside(mouseX, mouseY, x, y, x + size, y + size);
        graphics.fill(x, y, x + size, y + size, hover || avatarMenuOpen ? COLOR_BTN_HOVER : COLOR_BTN);
        drawBorder(graphics, x, y, x + size, y + size);
        PlayerHeads.drawHead(graphics, PlayerHeads.localSkin(), x + 1, y + 1, size - 2);
        clickables.add(new Clickable(x, y, x + size, y + size, true, false,
                () -> avatarMenuOpen = !avatarMenuOpen));
    }

    /** openOverlay: 打开某个覆盖层（顺手重置滚动与焦点）。 */
    private void openOverlay(Overlay target) {
        overlay = target;
        overlayScroll = 0;
        avatarMenuOpen = false;
        if (target == Overlay.SETTINGS) {
            overlayField = ResUiConfig.INSTANCE.backgroundFile();
        } else {
            marketFieldFocus = null;
        }
    }

    /** drawAvatarMenu: 头像下拉菜单（点菜单外的空白处会关掉）。 */
    private void drawAvatarMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = minecraft.player == null ? "未知玩家" : minecraft.player.getGameProfile().name();
        // 导航项都挪到左侧导航栏了，这里只留「设置」
        List<MenuItem> items = List.of(
                new MenuItem("设置", () -> openOverlay(Overlay.SETTINGS)));

        int width = 150;
        int rowHeight = 20;
        int headerHeight = 24;
        int height = headerHeight + items.size() * rowHeight + 4;
        // 菜单锚在左下角头像上方、朝上展开，别盖住左侧导航栏的按钮
        int x = PAD + GAP;
        int anchorTop = PAD + TITLE_BAR_HEIGHT + GAP;
        int avatarTop = PAD + (this.height - PAD) - GAP - AVATAR_SIZE;
        int y = Math.max(anchorTop, avatarTop - GAP - height);
        // 点菜单外的空白关闭（这一层排在菜单项之前，所以菜单项优先命中）
        clickables.add(new Clickable(0, 0, this.width, this.height, true, false,
                () -> avatarMenuOpen = false));
        graphics.fill(x, y, x + width, y + height, 0xF0181818);
        drawBorder(graphics, x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + headerHeight, 0xFF303030);
        PlayerHeads.drawHead(graphics, PlayerHeads.localSkin(), x + 4, y + 4, 16);
        graphics.drawString(this.font, name, x + 24, y + 8, COLOR_TEXT, true);
        int itemY = y + headerHeight;
        for (MenuItem item : items) {
            boolean hover = isInside(mouseX, mouseY, x, itemY, x + width, itemY + rowHeight);
            if (hover) {
                graphics.fill(x + 1, itemY, x + width - 1, itemY + rowHeight, COLOR_BTN_HOVER);
            }
            graphics.drawString(this.font, item.label(), x + 10, itemY + 6, COLOR_TEXT, true);
            clickables.add(new Clickable(x, itemY, x + width, itemY + rowHeight, true, false, item.action()));
            itemY += rowHeight;
        }
    }

    /** drawOverlay: 设置 / 领地商城覆盖层（都用共用的居中面板容器）。 */
    private void drawOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean settings = overlay == Overlay.SETTINGS;
        String title = settings ? "设置" : "领地商城";
        int width = settings ? 420 : Math.min(780, this.width - 40);
        int height = settings ? 380 : Math.min(470, this.height - 40);
        ResUiOverlayPanel.Layout layout = ResUiOverlayPanel.draw(this, graphics, mouseX, mouseY,
                title, width, height, overlayScroll, this::addOverlayClickable, this::overlayInside,
                new ResUiOverlayPanel.Content() {
                    @Override
                    public int draw(GuiGraphics g, int mx, int my,
                                    int contentX, int drawTop, int contentRight, int contentBottom) {
                        if (settings) {
                            // 设置页自己会返回内容底部 y，这里换算成高度
                            return ResUiSettingsPanel.draw(ResidenceListScreen.this, g, mx, my,
                                    contentX, drawTop, contentRight, drawTop, contentBottom,
                                    settingsInteraction(), settingsInput()) - drawTop;
                        }
                        return ResUiMarketPanel.draw(ResidenceListScreen.this, g, mx, my,
                                contentX, drawTop, contentRight, contentBottom,
                                marketFilter, marketFieldFocus, ResidenceListScreen.this::addOverlayClickable,
                                ResidenceListScreen.this::overlayInside,
                                ResidenceListScreen.this::focusMarketField, marketActions());
                    }
                },
                () -> overlay = Overlay.NONE);
        overlayScroll = layout.scroll;
    }

    /** addOverlayClickable: 覆盖层里的控件（不受列表的 inContent 判定影响）。 */
    private void addOverlayClickable(int x1, int y1, int x2, int y2, Runnable action) {
        clickables.add(new Clickable(x1, y1, x2, y2, true, false, action));
    }

    /** overlayInside: 覆盖层用的命中判定。 */
    private boolean overlayInside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
        return ResidenceListScreen.isInside(mouseX, mouseY, x1, y1, x2, y2);
    }

    /** focusMarketField: 商城的筛选输入框获得焦点。 */
    private void focusMarketField(String key) {
        marketFieldFocus = key;
    }

    /** marketActions: 商城里的买 / 租 / 传送（买和租都要过二次确认）。 */
    private ResUiMarketPanel.Actions marketActions() {
        return new ResUiMarketPanel.Actions() {
            @Override
            public void teleport(String residence) {
                ResUiClient.teleport(residence);
                ResidenceListScreen.this.onClose();
            }

            @Override
            public void buy(String residence) {
                ResidenceEntry entry = marketEntry(residence);
                String price = entry == null || entry.forSaleText().isBlank()
                        ? "标价" : entry.forSaleText();
                pendingConfirm = new Confirm("购买领地 " + residence,
                        "将从你的钱包扣除 " + price + "，领地立即归你所有（钱付给原主人）。",
                        "确认购买", () -> ResUiClient.buyResidence(residence));
            }

            @Override
            public void rent(String residence) {
                ResidenceEntry entry = marketEntry(residence);
                String detail = entry == null ? ""
                        : "，租期 " + entry.rentDays() + " 天";
                pendingConfirm = new Confirm("租用领地 " + residence,
                        "将从你的钱包扣除租金" + detail + "。到期后需要续租，否则会被收回。",
                        "确认租用", () -> ResUiClient.rentResidence(residence, false));
            }

            @Override
            public void unrent(String residence) {
                pendingConfirm = new Confirm("退租 " + residence,
                        "退租后你会立刻失去使用权，已付租金按 Residence 规则不退还。",
                        "确认退租", () -> ResUiClient.unrentResidence(residence));
            }

            @Override
            public void payRent(String residence) {
                ResUiClient.payRent(residence);
            }
        };
    }

    /** marketEntry: 按名字在商城数据里找一条（拿价格 / 天数给确认弹窗用）。 */
    private ResidenceEntry marketEntry(String name) {
        for (ResidenceEntry entry : ResidenceCache.INSTANCE.market()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    /** settingsInteraction: 设置覆盖层的点击注册（不受列表的 inContent 判定影响）。 */
    private ResUiSettingsPanel.Interaction settingsInteraction() {
        return new ResUiSettingsPanel.Interaction() {
            @Override
            public void clickable(int x1, int y1, int x2, int y2, Runnable action) {
                clickables.add(new Clickable(x1, y1, x2, y2, true, false, action));
            }

            @Override
            public boolean isInside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
                return ResidenceListScreen.isInside(mouseX, mouseY, x1, y1, x2, y2);
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

    private int drawList(GuiGraphics graphics, int mouseX, int mouseY, int x1, int y, int x2) {
        ResidenceCache cache = ResidenceCache.INSTANCE;
        if (!cache.error().isBlank()) {
            graphics.drawString(this.font, cache.error(), x1, y, COLOR_ERROR, true);
            y += ROW_H;
        }
        List<ResidenceEntry> entries = switch (tab) {
            case 0 -> cache.own();
            case 1 -> cache.rented();
            default -> cache.publicList();
        };
        if (entries.isEmpty()) {
            String hint = switch (tab) {
                case 1 -> "你目前没有租用中的领地";
                default -> "暂无数据";
            };
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
        // 市场状态直接显示在列表里（出售价 / 出租价 / 已出租给谁），不用点开弹窗
        if (ResUiConfig.INSTANCE.showMarketInList() && (entry.forSale() || entry.forRent() || entry.rented())) {
            String market = entry.marketText();
            info = info.isBlank() ? market : info + " · " + market;
        }
        if (ResUiConfig.INSTANCE.showCoordsInList()) {
            info = info.isBlank() ? entry.coordText() : info + " · " + entry.coordText();
        }
        if (!info.isBlank()) {
            graphics.drawString(this.font, info, x1 + 6, y + 15, COLOR_MUTED, true);
        }
        // 按钮从右往左排：传送 → 详细 → 管理 / 购买 / 租用
        int buttonWidth = 52;
        int gap = 4;
        int cursor = x2 - gap - buttonWidth;
        if (entry.tp()) {
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    "传送", false, true, true, () -> {
                        ResUiClient.teleport(entry.name());
                        this.onClose();
                    });
            cursor -= buttonWidth + gap;
        }
        drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                "详细", false, true, true, () -> detailEntry = entry);
        cursor -= buttonWidth + gap;
        if (tab == 0) {
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    "管理", false, true, true, () -> Minecraft.getInstance().setScreen(new ResidenceManageScreen(entry)));
        } else if (entry.forSale()) {
            // 别人挂牌出售的领地：直接给一个购买入口
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    entry.rented() ? "已出租" : "购买", false, true, !entry.rented(), () -> detailEntry = entry);
        } else if (entry.forRent()) {
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    entry.rented() ? "已出租" : "租用", false, true, !entry.rented(), () -> detailEntry = entry);
        } else if (tab == 1) {
            // 我租用中的领地：给自己留一个退租入口（在详细弹窗里二次确认）
            drawButton(graphics, mouseX, mouseY, cursor, y + 5, buttonWidth, BTN_H,
                    "退租", false, true, true, () -> detailEntry = entry);
        }
        return y + ROW_H + 3;
    }

    /** drawDetailOverlay: 领地详细信息弹窗（坐标等细节 + 传送 / 购买 / 租用 / 退租入口）。 */
    private void drawDetailOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        ResidenceEntry entry = detailEntry;
        boolean marketAction = (entry.forSale() && !entry.rented()) || (entry.forRent() && !entry.rented())
                || tab == 1;
        int panelWidth = 320;
        int panelHeight = marketAction ? 186 : 162;
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
        lineY = detailLine(graphics, x1 + 10, lineY, "可传送：", entry.tp() ? "是" : "否");
        if (marketAction) {
            lineY = detailLine(graphics, x1 + 10, lineY, "市场：", entry.marketText());
            if (tab == 1 && !entry.rentExpireText().isBlank()) {
                detailLine(graphics, x1 + 10, lineY, "到期：", entry.rentExpireText());
            }
        }

        int buttonY = y2 - 26;
        int buttonX = x1 + 10;
        if (entry.tp()) {
            drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "传送", false, true, true, () -> {
                ResUiClient.teleport(entry.name());
                this.onClose();
            });
            buttonX += 56;
        }
        if (tab == 1) {
            // 我租用中的领地：退租（二次确认）
            String rentPrice = entry.rentCostText().isBlank() ? entry.rentCost() + "" : entry.rentCostText();
            drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "退租", false, true, true, () ->
                    pendingConfirm = new Confirm("退租 " + entry.name(),
                            "退租后你会立刻失去使用权（已付租金 " + rentPrice + " / " + entry.rentDays()
                                    + " 天，按 Residence 规则不退还）。", "确认退租",
                            () -> ResUiClient.unrentResidence(entry.name())));
            buttonX += 56;
        } else if (entry.forSale() && !entry.rented()) {
            String price = entry.forSaleText().isBlank() ? entry.sellPrice() + "" : entry.forSaleText();
            drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "购买", false, true, true, () ->
                    pendingConfirm = new Confirm("购买领地 " + entry.name(),
                            "将从你的钱包扣除 " + price + "，领地立即归你所有（钱会付给原主人）。",
                            "确认购买", () -> ResUiClient.buyResidence(entry.name())));
            buttonX += 56;
        } else if (entry.forRent() && !entry.rented()) {
            String price = entry.rentCostText().isBlank() ? entry.rentCost() + "" : entry.rentCostText();
            drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "租用", false, true, true, () ->
                    pendingConfirm = new Confirm("租用领地 " + entry.name(),
                            "将从你的钱包扣除 " + price + "，租期 " + entry.rentDays()
                                    + " 天。到期后需要续租，否则会被收回。",
                            "确认租用", () -> ResUiClient.rentResidence(entry.name(), false)));
            buttonX += 56;
        }
        drawButton(graphics, mouseX, mouseY, buttonX, buttonY, 52, BTN_H, "关闭", false, true, true,
                () -> detailEntry = null);
    }

    /** drawConfirm: 红字二次确认弹窗（购买 / 租用 / 退租这类花钱或丢权限的操作都要过这一步）。 */
    private void drawConfirm(GuiGraphics graphics, int mouseX, int mouseY) {
        Confirm confirm = pendingConfirm;
        if (confirm == null) {
            return;
        }
        graphics.fill(0, 0, this.width, this.height, 0x99000000);
        int boxW = Math.min(380, this.width - 40);
        int boxH = 110;
        int boxX = (this.width - boxW) / 2;
        int boxY = (this.height - boxH) / 2;
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xF0202020);
        drawBorder(graphics, boxX, boxY, boxX + boxW, boxY + boxH);
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
        drawButton(graphics, mouseX, mouseY, buttonsX, buttonY, buttonW, BTN_H, "取消", false, true, true,
                () -> pendingConfirm = null);
        // 确认按钮用红色，防手滑
        boolean hover = isInside(mouseX, mouseY, buttonsX + buttonW + gap, buttonY,
                buttonsX + buttonW * 2 + gap, buttonY + BTN_H);
        int confirmX = buttonsX + buttonW + gap;
        graphics.fill(confirmX, buttonY, confirmX + buttonW, buttonY + BTN_H, hover ? 0xFFA33A3A : COLOR_DANGER);
        drawBorder(graphics, confirmX, buttonY, confirmX + buttonW, buttonY + BTN_H);
        int labelWidth = this.font.width(confirm.confirmLabel());
        graphics.drawString(this.font, confirm.confirmLabel(), confirmX + (buttonW - labelWidth) / 2,
                buttonY + (BTN_H - 8) / 2, COLOR_TEXT, true);
        clickables.add(new Clickable(confirmX, buttonY, confirmX + buttonW, buttonY + BTN_H, true, false, () -> {
            Runnable action = confirm.action();
            pendingConfirm = null;
            action.run();
        }));
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
        clickables.add(new Clickable(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE, true, false, () -> {
            // 覆盖层打开时，「x」先关覆盖层，再按一次才关整个界面
            if (overlay != Overlay.NONE) {
                overlay = Overlay.NONE;
                return;
            }
            this.onClose();
        }));
    }

    /**
     * drawButton: 通用按钮。
     * tabStyle=true 时按 active 高亮成页签（页签自身用 active 表示“当前页”）；
     * tabStyle=false 时是普通按钮，enabled=false 直接置灰且不可点。
     */
    private void drawButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int width, int height,
                            String label, boolean tabStyle, boolean active, boolean enabled, Runnable action) {
        boolean hover = enabled && isInside(mouseX, mouseY, x, y, x + width, y + height);
        int background;
        if (!enabled) {
            background = 0xFF3A3A3A;
        } else if (tabStyle) {
            background = active ? (hover ? COLOR_BTN_HOVER : COLOR_TAB_ACTIVE) : COLOR_TAB;
        } else {
            background = hover ? COLOR_BTN_HOVER : COLOR_BTN;
        }
        graphics.fill(x, y, x + width, y + height, background);
        drawBorder(graphics, x, y, x + width, y + height);
        int textWidth = this.font.width(label);
        graphics.drawString(this.font, label, x + (width - textWidth) / 2,
                y + (height - 8) / 2, enabled ? COLOR_TEXT : COLOR_MUTED, true);
        clickables.add(new Clickable(x, y, x + width, y + height, enabled, true, action));
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
        if (overlay != Overlay.NONE) {
            // 覆盖层自己滚
            overlayScroll = Math.max(0.0D, overlayScroll - scrollY * 16.0D);
            return true;
        }
        int viewHeight = contentBottom - contentTop;
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        scroll = Math.max(0.0D, Math.min(maxScroll, scroll - scrollY * 20.0D));
        return true;
    }

    /** focusedFilterKey: 覆盖层里当前聚焦的输入框 key（没有焦点返回 null）。 */
    private String focusedFilterKey() {
        if (overlay == Overlay.MARKET) {
            return marketFieldFocus;
        }
        if (overlay == Overlay.SETTINGS && settingsFocusKey != null) {
            return settingsFocusKey;
        }
        return null;
    }

    /** typeInto: 把输入打进当前聚焦的筛选 / 设置输入框。 */
    private boolean typeInto(String typed) {
        String key = focusedFilterKey();
        if (key == null) {
            return false;
        }
        if (overlay == Overlay.MARKET) {
            ResUiMarketPanel.Filter filter = marketFilter;
            switch (key) {
                case "minArea" -> filter.minArea = appendLimited(filter.minArea, typed);
                case "maxArea" -> filter.maxArea = appendLimited(filter.maxArea, typed);
                case "minPrice" -> filter.minPrice = appendLimited(filter.minPrice, typed);
                case "maxPrice" -> filter.maxPrice = appendLimited(filter.maxPrice, typed);
                case "keyword" -> filter.keyword = appendLimited(filter.keyword, typed);
                default -> {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** backspace: 删掉当前聚焦输入框的最后一个字符。 */
    private boolean backspace() {
        String key = focusedFilterKey();
        if (key == null) {
            return false;
        }
        if (overlay == Overlay.MARKET) {
            switch (key) {
                case "minArea" -> marketFilter.minArea = trimLast(marketFilter.minArea);
                case "maxArea" -> marketFilter.maxArea = trimLast(marketFilter.maxArea);
                case "minPrice" -> marketFilter.minPrice = trimLast(marketFilter.minPrice);
                case "maxPrice" -> marketFilter.maxPrice = trimLast(marketFilter.maxPrice);
                case "keyword" -> marketFilter.keyword = trimLast(marketFilter.keyword);
                default -> {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** appendLimited: 输入框的长度上限。 */
    private static String appendLimited(String text, String typed) {
        return text.length() + typed.length() > 24 ? text : text + typed;
    }

    private static String trimLast(String text) {
        return text == null || text.isEmpty() ? "" : text.substring(0, text.length() - 1);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (overlay != Overlay.NONE) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                // Esc：先退出输入焦点，再按一次关面板
                if (focusedFilterKey() != null) {
                    marketFieldFocus = null;
                    settingsFocusKey = null;
                } else {
                    overlay = Overlay.NONE;
                }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && backspace()) {
                return true;
            }
            if (focusedFilterKey() != null) {
                // 焦点在输入框时吃掉其它按键，避免触发原版快捷键
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (overlay != Overlay.NONE && event.isAllowedChatCharacter() && typeInto(event.codepointAsString())) {
            return true;
        }
        return super.charTyped(event);
    }

    private record Clickable(int x1, int y1, int x2, int y2, boolean enabled, boolean inContent, Runnable action) {
    }
}