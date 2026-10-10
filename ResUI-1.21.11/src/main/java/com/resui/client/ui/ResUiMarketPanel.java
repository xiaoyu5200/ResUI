package com.resui.client.ui;

import com.resui.client.bridge.ResidenceCache;
import com.resui.client.bridge.ResidenceEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * ResUiMarketPanel: 领地商城 —— 把服务器上**别人**挂牌出售 / 出租的领地列出来，支持筛选与排序。
 *
 * 数据来自服务端快照的 market 字段（每行带 size / sellPrice / rentCost / rentDays 等标量）。
 * 面板只负责展示与筛选，真正的买 / 租动作走 {@link Actions} 回调，由界面弹二次确认。
 */
@Environment(EnvType.CLIENT)
public final class ResUiMarketPanel {
    /** 商城的三种视图。 */
    public static final int TAB_ALL = 0;
    public static final int TAB_SALE = 1;
    public static final int TAB_RENT = 2;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFBDBDBD;
    private static final int COLOR_ROW = 0x55202020;
    private static final int COLOR_ROW_ALT = 0x33202020;
    private static final int COLOR_BORDER = 0x40FFFFFF;
    private static final int COLOR_BTN_ACTIVE = 0xFF6E6E6E;
    private static final int COLOR_SALE = 0xFF7FB2FF;
    private static final int COLOR_RENT = 0xFF9BE59B;
    private static final int ROW_H = 30;

    /** Actions: 商城里的操作（由界面实现，负责二次确认与发请求）。 */
    public interface Actions {
        void teleport(String residence);

        void buy(String residence);

        void rent(String residence);

        void unrent(String residence);

        void payRent(String residence);
    }

    /** Filter: 筛选条件（由界面持有，切页/重开面板时保留）。 */
    public static final class Filter {
        public int tab = TAB_ALL;
        /** 排序列：0 = 价格/租金升序，1 = 降序，2 = 面积降序，3 = 名称。 */
        public int sort;
        public String keyword = "";
        public String minArea = "";
        public String maxArea = "";
        public String minPrice = "";
        public String maxPrice = "";
        /** 是否只看可传送的。 */
        public boolean onlyTp;

        public void clear() {
            minArea = "";
            maxArea = "";
            minPrice = "";
            maxPrice = "";
            keyword = "";
            onlyTp = false;
        }

        public boolean hasAnyFilter() {
            return !minArea.isBlank() || !maxArea.isBlank() || !minPrice.isBlank() || !maxPrice.isBlank()
                    || !keyword.isBlank() || onlyTp;
        }
    }

    private ResUiMarketPanel() {
    }

    /** list: 按当前筛选条件算出要显示的领地。 */
    public static List<ResidenceEntry> list(Filter filter) {
        List<ResidenceEntry> result = new ArrayList<>();
        long minArea = parseLong(filter.minArea, -1);
        long maxArea = parseLong(filter.maxArea, -1);
        long minPrice = parseLong(filter.minPrice, -1);
        long maxPrice = parseLong(filter.maxPrice, -1);
        String keyword = filter.keyword.trim().toLowerCase(Locale.ROOT);
        for (ResidenceEntry entry : ResidenceCache.INSTANCE.market()) {
            boolean forSale = entry.forSale();
            boolean forRent = entry.forRent();
            if (!forSale && !forRent) {
                continue;
            }
            if (filter.tab == TAB_SALE && !forSale) {
                continue;
            }
            if (filter.tab == TAB_RENT && !forRent) {
                continue;
            }
            if (filter.onlyTp && !entry.tp()) {
                continue;
            }
            if (!keyword.isEmpty()) {
                String haystack = (entry.name() + " " + entry.owner() + " " + entry.world()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(keyword)) {
                    continue;
                }
            }
            // 面积筛选：按水平面积（m²，与 /res info 的显示一致）
            long area = entry.baseSize();
            if (minArea >= 0 && area < minArea) {
                continue;
            }
            if (maxArea >= 0 && area > maxArea) {
                continue;
            }
            // 价格筛选：出售看售价，出租看租金；两种都挂时按当前页签取对应价格
            long price = filter.tab == TAB_SALE ? entry.sellPrice()
                    : filter.tab == TAB_RENT ? entry.rentCost()
                    : (forSale ? entry.sellPrice() : entry.rentCost());
            if (minPrice >= 0 && price < minPrice) {
                continue;
            }
            if (maxPrice >= 0 && price > maxPrice) {
                continue;
            }
            result.add(entry);
        }
        Comparator<ResidenceEntry> comparator = switch (filter.sort) {
            case 1 -> Comparator.comparingLong(ResUiMarketPanel::sortPrice).reversed();
            case 2 -> Comparator.comparingLong(ResidenceEntry::baseSize).reversed();
            case 3 -> Comparator.comparing(ResidenceEntry::name, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingLong(ResUiMarketPanel::sortPrice);
        };
        result.sort(comparator.thenComparing(ResidenceEntry::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** sortPrice: 排序用的价格（出售优先用售价，否则用租金）。 */
    private static long sortPrice(ResidenceEntry entry) {
        return entry.forSale() ? entry.sellPrice() : entry.rentCost();
    }

    private static long parseLong(String text, long fallback) {
        try {
            String trimmed = text == null ? "" : text.trim();
            return trimmed.isEmpty() ? fallback : Long.parseLong(trimmed);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /**
     * draw: 画商城内容，返回内容高度。
     * 筛选条的输入框只在 {@code focusedFilter} 匹配时显示光标，键盘输入由界面转发。
     */
    public static int draw(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                           int contentX, int contentTop, int contentRight, int contentBottom,
                           Filter filter, String focusedFilter,
                           ResUiOverlayPanel.Clickable clickable, ResUiOverlayPanel.Inside inside,
                           java.util.function.Consumer<String> focus, Actions actions) {
        int y = contentTop;
        List<ResidenceEntry> entries = list(filter);
        int total = ResidenceCache.INSTANCE.market().size();

        // ---- 视图与排序 ----
        int cursor = contentX;
        cursor = tab(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "全部 " + total, filter.tab == TAB_ALL, () -> filter.tab = TAB_ALL);
        cursor = tab(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "出售", filter.tab == TAB_SALE, () -> filter.tab = TAB_SALE);
        cursor = tab(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "出租", filter.tab == TAB_RENT, () -> filter.tab = TAB_RENT);
        y += 22;

        cursor = contentX;
        cursor = smaller(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "价格 ↑", filter.sort == 0, () -> filter.sort = 0);
        cursor = smaller(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "价格 ↓", filter.sort == 1, () -> filter.sort = 1);
        cursor = smaller(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "面积大", filter.sort == 2, () -> filter.sort = 2);
        cursor = smaller(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, "名称", filter.sort == 3, () -> filter.sort = 3);
        smaller(screen, graphics, mouseX, mouseY, clickable, inside, contentTop, contentBottom,
                cursor, y, filter.onlyTp ? "只可传送 ✓" : "只可传送", filter.onlyTp,
                () -> filter.onlyTp = !filter.onlyTp);
        y += 22;

        // ---- 筛选输入 ----
        int fieldWidth = 62;
        int x = contentX;
        x = filterField(screen, graphics, clickable, x, y, fieldWidth, "minArea", filter.minArea,
                "面积≥", focusedFilter, focus);
        x = filterField(screen, graphics, clickable, x, y, fieldWidth, "maxArea", filter.maxArea,
                "面积≤", focusedFilter, focus);
        x = filterField(screen, graphics, clickable, x, y, fieldWidth, "minPrice", filter.minPrice,
                "价格≥", focusedFilter, focus);
        x = filterField(screen, graphics, clickable, x, y, fieldWidth, "maxPrice", filter.maxPrice,
                "价格≤", focusedFilter, focus);
        int keywordWidth = Math.max(80, contentRight - x - 4);
        filterField(screen, graphics, clickable, x, y, keywordWidth, "keyword", filter.keyword,
                "搜名字 / 所有者", focusedFilter, focus);
        y += 20;
        graphics.drawString(screen.getFont(),
                "面积单位 m²（水平面积）；价格对出售是售价、对出租是租金/期",
                contentX, y, COLOR_MUTED, true);
        y += 14;
        int clearX = contentX;
        if (filter.hasAnyFilter()) {
            ResUiOverlayPanel.button(screen, graphics, mouseX, mouseY, clickable, inside,
                    contentTop, contentBottom, clearX, y, "清空筛选", true, filter::clear);
            y += 22;
        }
        graphics.drawString(screen.getFont(), "共 " + entries.size() + " 条结果", contentX, y, COLOR_TEXT, true);
        y += 14;

        // ---- 列表 ----
        if (entries.isEmpty()) {
            y = ResUiOverlayPanel.hint(screen, graphics, contentX, y,
                    total == 0 ? "服务器上现在没有别人挂牌的领地（让他们在「市场出租」页挂牌）"
                            : "没有符合条件的领地，试试放宽筛选条件");
            return y - contentTop;
        }
        int index = 0;
        for (ResidenceEntry entry : entries) {
            boolean alternate = index % 2 == 0;
            graphics.fill(contentX, y, contentRight, y + ROW_H, alternate ? COLOR_ROW : COLOR_ROW_ALT);
            border(graphics, contentX, y, contentRight, y + ROW_H);
            graphics.drawString(screen.getFont(), entry.name(), contentX + 6, y + 4, COLOR_TEXT, true);
            String status = entry.marketText();
            graphics.drawString(screen.getFont(), status, contentX + 6, y + 16,
                    entry.forSale() ? COLOR_SALE : COLOR_RENT, true);
            String meta = entry.owner() + " · " + entry.world() + " · "
                    + entry.baseSize() + "m² · " + entry.areas() + " 区域"
                    + (entry.tp() ? " · 可传送" : "");
            graphics.drawString(screen.getFont(), meta, contentX + 150, y + 16, COLOR_MUTED, true);

            int buttonX = contentRight - 6 - 46;
            if (entry.tp()) {
                ResUiOverlayPanel.button(screen, graphics, mouseX, mouseY, clickable, inside,
                        contentTop, contentBottom, buttonX, y + 6, "传送", true,
                        () -> actions.teleport(entry.name()));
                buttonX -= 50;
            }
            if (entry.forSale()) {
                ResUiOverlayPanel.button(screen, graphics, mouseX, mouseY, clickable, inside,
                        contentTop, contentBottom, buttonX, y + 6, "购买", entry.sellPrice() > 0,
                        () -> actions.buy(entry.name()));
                buttonX -= 50;
            } else if (entry.forRent() && entry.rented()) {
                ResUiOverlayPanel.button(screen, graphics, mouseX, mouseY, clickable, inside,
                        contentTop, contentBottom, buttonX, y + 6, "已出租", false,
                        () -> actions.payRent(entry.name()));
                buttonX -= 56;
            } else if (entry.forRent()) {
                ResUiOverlayPanel.button(screen, graphics, mouseX, mouseY, clickable, inside,
                        contentTop, contentBottom, buttonX, y + 6, "租用", entry.rentCost() > 0,
                        () -> actions.rent(entry.name()));
                buttonX -= 50;
            }
            index++;
            y += ROW_H + 2;
        }
        return y - contentTop;
    }

    /** filterField: 带独立焦点的紧凑筛选输入框。 */
    private static int filterField(Screen screen, GuiGraphics graphics, ResUiOverlayPanel.Clickable clickable,
                                   int x, int y, int width, String key, String value, String hint,
                                   String focusedFilter, java.util.function.Consumer<String> focus) {
        boolean focused = key.equals(focusedFilter);
        graphics.fill(x, y, x + width, y + 16, focused ? 0xFF101010 : 0xFF1A1A1A);
        border(graphics, x, y, x + width, y + 16);
        boolean empty = value == null || value.isEmpty();
        graphics.drawString(screen.getFont(), empty ? hint : value, x + 4, y + 5,
                empty ? COLOR_MUTED : COLOR_TEXT, true);
        if (focused) {
            int caretX = x + 4 + screen.getFont().width(value);
            graphics.fill(caretX + 1, y + 3, caretX + 2, y + 13, 0xFFDDDDDD);
        }
        clickable.add(x, y, x + width, y + 16, () -> focus.accept(key));
        return x + width + 4;
    }

    private static int tab(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                           ResUiOverlayPanel.Clickable clickable, ResUiOverlayPanel.Inside inside,
                           int contentTop, int contentBottom,
                           int x, int y, String label, boolean active, Runnable action) {
        int width = 16 + screen.getFont().width(label);
        boolean hover = inside.test(mouseX, mouseY, x, y, x + width, y + 20);
        int background = active ? COLOR_BTN_ACTIVE : (hover ? 0xFF6C6C6C : 0xFF3F3F3F);
        graphics.fill(x, y, x + width, y + 20, background);
        border(graphics, x, y, x + width, y + 20);
        graphics.drawString(screen.getFont(), label, x + (width - screen.getFont().width(label)) / 2,
                y + 6, COLOR_TEXT, true);
        if (y + 20 > contentTop && y < contentBottom) {
            clickable.add(x, y, x + width, y + 20, action);
        }
        return x + width + 4;
    }

    private static int smaller(Screen screen, GuiGraphics graphics, int mouseX, int mouseY,
                               ResUiOverlayPanel.Clickable clickable, ResUiOverlayPanel.Inside inside,
                               int contentTop, int contentBottom,
                               int x, int y, String label, boolean active, Runnable action) {
        int width = 12 + screen.getFont().width(label);
        boolean hover = inside.test(mouseX, mouseY, x, y, x + width, y + 18);
        int background = active ? COLOR_BTN_ACTIVE : (hover ? 0xFF6C6C6C : 0xFF4A4A4A);
        graphics.fill(x, y, x + width, y + 18, background);
        border(graphics, x, y, x + width, y + 18);
        graphics.drawString(screen.getFont(), label, x + (width - screen.getFont().width(label)) / 2,
                y + 5, COLOR_TEXT, true);
        if (y + 18 > contentTop && y < contentBottom) {
            clickable.add(x, y, x + width, y + 18, action);
        }
        return x + width + 4;
    }

    private static void border(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y1 + 1, COLOR_BORDER);
        graphics.fill(x1, y2 - 1, x2, y2, COLOR_BORDER);
        graphics.fill(x1, y1, x1 + 1, y2, COLOR_BORDER);
        graphics.fill(x2 - 1, y1, x2, y2, COLOR_BORDER);
    }
}
