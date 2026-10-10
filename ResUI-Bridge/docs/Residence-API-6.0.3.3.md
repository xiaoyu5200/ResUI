# Residence API 精确对照表（编译期 6.0.0.1 / 运行期 6.0.3.3）

所有签名均由 `javap -p`（必要时 `-c` 读字节码语义）实测得出：
- 编译期 jar：`D:\xiaoyu\领地mod\ResUI-Bridge\libs\Residence6.0.0.1.jar`
- 运行期 jar：`D:\1.21.11\plugins\[领地]Residence6.0.3.3.jar`

未在任何 `javap` 输出中出现的方法名，本文档一律不写。

---

## 附 0. API 入口（先看这个）

```java
Residence rs = com.bekvon.bukkit.residence.Residence.getInstance();
// 全部为 public 实例方法（javap 实测）：
rs.getResidenceManager()    // com.bekvon.bukkit.residence.protection.ResidenceManager
rs.getSelectionManager()    // com.bekvon.bukkit.residence.selection.SelectionManager
rs.getPermissionManager()   // com.bekvon.bukkit.residence.permissions.PermissionManager
rs.getPlayerManager()       // com.bekvon.bukkit.residence.protection.PlayerManager
rs.getRentManager()         // com.bekvon.bukkit.residence.economy.rent.RentManager
rs.getTransactionManager()  // com.bekvon.bukkit.residence.economy.TransactionManager
rs.getLeaseManager()        // com.bekvon.bukkit.residence.protection.LeaseManager
rs.getChatManager()         // com.bekvon.bukkit.residence.chat.ChatManager
rs.getEconomyManager()      // com.bekvon.bukkit.residence.economy.EconomyInterface
rs.getConfigManager()       // com.bekvon.bukkit.residence.ConfigManager
rs.isResAdminOn(Player)     // boolean
rs.validName(String)        // boolean
rs.getServerLandName()      // String
rs.getServerUUID()          // UUID
rs.isDisabledWorld(World)   // boolean
```

静态门面（`-p` 实测全部为 `public static`，可直接用，不需要先拿 plugin 实例）：

```java
com.bekvon.bukkit.residence.api.ResidenceApi.getResidenceManager()  // ResidenceInterface
com.bekvon.bukkit.residence.api.ResidenceApi.getPlayerManager()     // ResidencePlayerInterface
com.bekvon.bukkit.residence.api.ResidenceApi.getMarketRentManager() // MarketRentInterface
com.bekvon.bukkit.residence.api.ResidenceApi.getMarketBuyManager()  // MarketBuyInterface
com.bekvon.bukkit.residence.api.ResidenceApi.getChatManager()       // ChatInterface
```

**全局坑（每条都实测过）**

1. **没有异步 API**。除 `ClaimedResidence.getTeleportLocationASYNC(Player, boolean)` 外，所有操作都必须主线程执行（`LeaseManager`/`RentManager` 内部用 `CMIScheduler.runTask`）。
2. **改了内存不会立刻落盘**。服务器 `config.yml` 的 `SaveInterval: 10`（分钟）；`ResidenceManager.save()` 是 public 的，可以手动强制存（很重，别高频调）。改 `enterMessage/leaveMessage`、黑名单、命令名单、owner、改名后建议跟一次保存，或者在 UI 里提示 10 分钟内生效。
3. **6.0.0.1 → 6.0.3.3 的破坏性差异**（只列会影响桥接的）：
   - `LeaseManager.load(Map)`：6.0.0.1 返回 `LeaseManager`，6.0.3.3 变成 `void`。**不要使用返回值**，否则运行期 `NoSuchMethodError`。
   - `ClaimedResidence.getEnterMessage()/getLeaveMessage()` 两边都有，没有 `MessageType` 枚举（6.0.3.3 里 `ELMessageType` 已删除，只剩 `containers/GenMessageType`：`ActionBar / TitleBar / ChatBox`，那是"消息显示位置"不是进/出消息类型）。**不要 import 任何 `ELMessageType`。**
   - `ItemList.list` 字段类型：6.0.0.1 `List<Material>` → 6.0.3.3 `Set<Material>`。**不要反射碰它**，用 `toArray()`。
   - `FlagPermissions.getCMDWhiteList()/getCMDBlackList()` 两边都是 public（注意是 **CMD** 全大写前缀，和 `ClaimedResidence.getCmdWhiteList()` 不是一个东西）。
   - 6.0.3.3 新增 `CuboidArea.clone()`、`SelectionManager.placeLoc(...)`、`ClaimedResidence.getMainAreaName()/getAreaNameByLoc()/containsArea()`、`PermissionGroup.getGroup(Player/UUID/UUID,String)`。用 6.0.0.1 编译没问题，但**反向（在 6.0.0.1 上跑编译自 6.0.3.3 的桥接）会挂** —— 只对 6.0.0.1 里存在的 API 编译。

---

## 1. 子领地 subzone

### 结论
`/res subzone <领地> <子领地名>` 的 API 路径 = **选区 + `ClaimedResidence.addSubzone(...)`**，等价于 `/res select` 选好两点后调用。父→子列表用 `getSubzones()`/`getSubzonesMap()`（只含直接子级），递归要自己走。删除走 `removeSubzone(...)`。

### 精确签名

```java
// 判断身份
public boolean isSubzone();                                  // ClaimedResidence
public ClaimedResidence getParent();                         // 顶层领地返回 null
public ClaimedResidence getTopParent();
public String getTopParentName();
public boolean isTopArea();
public int getSubzoneDeep();
public int getSubzoneDeep(int start);                        // 内部自增用，别当"从第 N 层算"
public int getZoneDepth();

// 父 → 子（只有直接子级）
public List<ClaimedResidence> getSubzones();
public Map<String, ClaimedResidence> getSubzonesMap();
public String[] getSubzoneList();                            // 直接子级的名字（无路径前缀）
public int getSubzonesAmount(Boolean rentedOnly);            // 参数可传 null
public ClaimedResidence getSubzone(String name);             // 本层内查子级，不递归
public ClaimedResidence getSubzoneByLoc(Location loc);       // 递归向下找最深的那个
public ClaimedResidence getSubzoneByLoc(Location loc);
public String getSubzoneNameByRes(ClaimedResidence child);   // ClaimedResidence 与 ResidenceManager 上都有

// 创建（等价 /res subzone）
public boolean addSubzone(String subzoneName, Location loc1, Location loc2);
public boolean addSubzone(Player player, Location loc1, Location loc2, String subzoneName, boolean ignoreY);
public boolean addSubzone(Player player, String subzoneName, boolean ignoreY);
public boolean addSubzone(Player player, String subzoneName, Location loc1, Location loc2, String ignoreName, boolean ignoreY);

// 删除
public boolean removeSubzone(String subzoneName);
public boolean removeSubzone(Player player, String subzoneName, boolean ignoreY);

// 改子领地名
public boolean renameSubzone(String oldName, String newName);
public boolean renameSubzone(Player player, String oldName, String newName, boolean ignoreY);
public boolean renameSubzone(CommandSender sender, String oldName, String newName, boolean ignoreY);
```

### 创建子领地：怎么把"选区"喂进去

官方 `/res subzone` 的字节码（`commands/subzone`）就是：

```java
// 实测反编译出的真实调用序列
if (Residence.getInstance().getSelectionManager().hasPlacedBoth(player)) {
    ClaimedResidence parent = Residence.getInstance().getResidenceManager().getByName(resName);
    parent.addSubzone(player,
        Residence.getInstance().getSelectionManager().getPlayerLoc1(player),
        Residence.getInstance().getSelectionManager().getPlayerLoc2(player),
        subzoneName, ignoreY);
} else {
    // "Select_Points" 提示：你还没选区
}
```

所以**你自己的 UI 里要点两个点或手动传坐标**时：

```java
Residence rs = Residence.getInstance();
SelectionManager sel = rs.getSelectionManager();

// 方案 A：用玩家已经在 /res select 里选好的两个点（最贴近官方行为）
if (!sel.hasPlacedBoth(player)) { /* 提示玩家先选区 */ }
boolean ok = parent.addSubzone(player,
        sel.getPlayerLoc1(player), sel.getPlayerLoc2(player), newName, false);

// 方案 B：替玩家写选区再创建（placeLoc1/2 的第三个参数 = 是否触发 afterSelectionUpdate，官方传 true）
sel.placeLoc1(player, loc1, true);
sel.placeLoc2(player, loc2, true);
boolean ok2 = parent.addSubzone(player, loc1, loc2, newName, false);
```

相关选区 API（全部实测）：

```java
public SelectionManager.Selection getSelection(Player player);   // null 安全，会自动创建并放进 map
public Location getPlayerLoc1(Player player);
public Location getPlayerLoc2(Player player);
public Location getPlayerLoc1(String playerName);
public Location getPlayerLoc2(String playerName);
public CuboidArea getSelectionCuboid(Player player);              // 未选两点返回 null
public CuboidArea getSelectionCuboid(String playerName);
public boolean hasPlacedBoth(Player player);
public boolean hasPlacedBoth(String playerName);
public void placeLoc1(Player player, Location loc);
public void placeLoc1(Player player, Location loc, boolean update);
public void placeLoc2(Player player, Location loc);
public void placeLoc2(Player player, Location loc, boolean update);
public void clearSelection(Player player);
public void selectChunk(Player player);
public void selectBySize(Player player, int x, int y, int z);
public boolean contract(Player player, int amount);
public boolean contract(Player player, double amount);
public boolean contract(Player player, double amount, boolean ignoreY);
public void modify(Player player, boolean expand, int amount);     // expand=true 扩张
public void modify(Player player, boolean expand, double amount);
public void vert(Player player, boolean ignoreY);   // 选区拉满世界高度
public void sky(Player player, boolean ignoreY);
public void bedrock(Player player, boolean ignoreY);
```

### 坑（`addSubzone` 字节码里实际存在的检查，顺序即失败顺序）

1. `Residence.validName(name)` 为 false → 直接 false（非法字符/长度）。
2. **两个点必须都在父领地内**：`if (!containsLoc(loc1) || !containsLoc(loc2))` → 返回 false（`Subzone_SelectInside`）。所以不能"只放一个点在领地内"。
3. 同名子领地已存在 → `Subzone_Exists`，返回 false。
4. 权限：`parent.getPermissions().hasResidencePermission(sender, true)` 不过时，还要 `playerHas(playerName, Flags.subzone, false)` 或 `playerHas(name, Flags.admin, false)`，否则 `General_NoPermission` → 返回 false。
5. `getSubzoneList().length + 1 > ResidencePlayer.getMaxSubzones()` → `Subzone_MaxAmount`。
6. `parent.getZoneDepth() + 1 > ResidencePlayer.getMaxSubzoneDepth()` → `Subzone_MaxDepth`。
7. 与已有子领地碰撞 `checkCollision` → `Subzone_Collide`，并且会弹 `Visualizer` 边界可视化。
8. 成功后会 `callEvent(new ResidenceSubzoneCreationEvent(...))`。
9. `ignoreY` 传 true 时只在 XZ 上判定越界/碰撞（`config.yml: Selection.IgnoreYInSubzone: false` 是它的默认值来源）。

**UI 建议**：不要只看返回值，因为返回值只有 true/false，拿不到"哪一步失败"。要么先做前置校验（`hasPlacedBoth` + `containsLoc` + `getSubzone(name) != null` + `getMaxSubzones()`），要么监听 `ResidenceSubzoneCreationEvent` 做兜底。

### 删除子领地

```java
// 返回 boolean；内部会做权限检查（parent.isOwner(player) || parent.getPermissions().playerHas(player, Flags.admin, OnlyTrue))
// 以及 config 的 PreventSubZoneRemoval（服务器上为 true）
child.removeSubzone(childName);              // 在"父"上调，参数是子级名字（本层名，不是全路径）
parent.removeSubzone(player, childName, false);
```

`config.yml: PreventSubZoneRemoval: true` → 父领地所有者与子领地所有者不同时，普通玩家删不掉子领地（`/res remove` 里也会用 `Subzone_CantDeleteNotOwnerOfParent` 拦）。

---

## 2. 区域 cuboid

### 结论
`CuboidArea` **没有 `getName()`**（javap 里确实不存在）——区域名字只存在于 `Map` 的 key 里。全部区域用 `getAreaArray()`（`CuboidArea[]`）或 `getAreaMap()`。主区域 key 固定是字符串 `"main"`；子领地的区域 key 形如 `<子领地名>.<序号>`。`ClaimedResidence` **没有** `setMainArea(String)` 这个方法。

### 精确签名

```java
// ClaimedResidence —— 区域集合
public Map<String, CuboidArea> getAreaMap();
public CuboidArea[] getAreaArray();
public String[] getAreaList();
public CuboidArea getArea(String areaName);
public CuboidArea getCuboidAreabyName(String areaName);
public CuboidArea getMainArea();
public String getMainAreaName();               // 仅 6.0.3.3 有
public CuboidArea getAreaByLoc(Location loc);
public String getAreaIDbyLoc(Location loc);    // 返回区域 key
public String getAreaNameByLoc(Location loc);  // 仅 6.0.3.3 有
public int getAreaCount();
public boolean containsArea(String areaName);  // 仅 6.0.3.3 有
public long getTotalSize();
public long getXZSize();
public boolean checkCollision(CuboidArea area);
public boolean containsLoc(Location loc);

// 区域增删改
public boolean addArea(CuboidArea area, String areaName);
public boolean addArea(Player player, CuboidArea area, String areaName, boolean ignoreY);
public boolean addArea(Player player, CuboidArea area, String areaName, boolean ignoreY, boolean ignoreLimits);
public boolean replaceArea(CuboidArea area, String areaName);
public boolean replaceArea(Player player, CuboidArea area, String areaName, boolean ignoreY);
public void removeArea(String areaName);
public void removeArea(Player player, String areaName, boolean ignoreY);
public boolean renameArea(String oldName, String newName);
public boolean renameArea(Player player, String oldName, String newName, boolean ignoreY);
// 尺寸前置校验（addArea 内部会调）
public boolean isBiggerThanMin(Player player, CuboidArea area, boolean ignoreLimits);
public boolean isBiggerThanMinSubzone(Player player, CuboidArea area, boolean ignoreLimits);
public boolean isSmallerThanMax(Player player, CuboidArea area, boolean ignoreLimits);
public boolean isSmallerThanMaxSubzone(Player player, CuboidArea area, boolean ignoreLimits);

// org.bukkit.util.Vector / Location 侧
public Vector getHighVector();
public Vector getLowVector();
public Location getHighLoc();
public Location getLowLoc();
public Location getHighLocation();
public Location getLowLocation();
public void setHighVector(Vector v);
public void setLowVector(Vector v);
public void setHighLocation(Location loc);
public void setLowLocation(Location loc);
public World getWorld();
public String getWorldName();
public double getCost(PermissionGroup group);

// CuboidArea 的尺寸
public long getSize();     // (xSize * ySize * zSize)
public int getXSize();     // high.getBlockX() - low.getBlockX() + 1
public int getYSize();
public int getZSize();
public boolean isAreaWithinArea(CuboidArea other);
public boolean containsLoc(Location loc);
public boolean containsLoc(Vector vec, String worldName);
public boolean checkCollision(CuboidArea other);
public String newSave();   // "x:y:z:x:y:z" 序列化
public static CuboidArea newLoad(String worldName, String data) throws Exception;
public List<ResidenceManager.ChunkRef> getChunks();
public CuboidArea clone();  // 仅 6.0.3.3 有
```

已用过的 `getAreaCount()` / `getTotalSize()` / `getXZSize()` 出处确认：**三者都在 `ClaimedResidence`**，"main" 与子区域统一计入区域数量但子领地本身不算区域。

### 坑

- `getAreaArray()` 拿的是**快照数组**（`areas.values().toArray()`），改它不影响领地，要改必须 `replaceArea`/`addArea`/`removeArea`。
- `getAreaMap()` 返回的是**活引用**（`areas` 字段本身）。往里面 `put` 不会触发碰撞/限额/事件/存档逻辑，**不要直接改**，会写出坏存档。
- `addArea` 与 `replaceArea` 的区别：`replaceArea` 只改同一个 key 的区域（不能新建），`addArea` 会新增 key。**"扩大/缩小"官方实现的其实是 `replaceArea(area, "main")`**（见第 8 节）。
- `removeArea("main")` 不会删领地——主区域删掉会导致领地没有区域，别做。删领地请用 `removeSubzone` 或 `ResidenceManager.removeResidence`。

---

## 3. 黑名单 / 忽略名单（`/res lset`）

### 结论
**没有 `getBlackList()`/`setBlackList()`。** 真实入口在 `ClaimedResidence` 上：

```java
public ResidenceItemList getItemBlacklist();    // 注意是小写 l + ist：getItemBlacklist
public ResidenceItemList getItemIgnoreList();   // 这里是 IgnoreList（大写 L）
```

`ResidenceItemList extends ItemList`，内部 `protected List<Material> list`（6.0.3.3 是 `Set<Material>`，所以**只能走方法，别反射/别强转**）。

### 精确签名

```java
// ItemList（父类）
public ItemList.ListType getType();
public boolean contains(Material mat);
public void add(Material mat);                 // 去重后添加
public void remove(Material mat);
public boolean toggle(Material mat);           // 在列表里→移除并返回 false；不在→添加并返回 true
public boolean isAllowed(Material mat);
public boolean isIgnored(Material mat);
public boolean isListed(Material mat);
public int getListSize();
public Material[] toArray();
public void printList(Player player);          // 就是 /res lset <res> info 打印的内容
public Map<String, Object> save();
public static ItemList load(Map<String, Object> map);
public static ItemList readList(ConfigurationSection section);

// ResidenceItemList
public ResidenceItemList(Residence plugin, ClaimedResidence res, ItemList.ListType type);
public void playerListChange(Player player, Material mat, boolean ignorePermissions);
public static ResidenceItemList load(Residence plugin, ClaimedResidence res, Map<String, Object> map);
```

枚举：

```
ItemList.ListType = { BLACKLIST, WHITELIST, IGNORELIST, OTHER }
```

### 稳定调用示例（照抄官方 `/res lset` 的逻辑）

```java
// 官方 lset 实测序列：
// ClaimedResidence res = ResidenceManager.getByName(name);
// res.getItemBlacklist().playerListChange(player, material, ignorePerms);
//   ↑ 内部：PermissionGroup.itemListAccess() + ResidencePermissions.hasResidencePermission(...)
//           然后 ItemList.toggle(material)，并给玩家发 General_ListMaterialAdd / General_ListMaterialRemove

ClaimedResidence res = Residence.getInstance().getResidenceManager().getByName(name);
Material mat = Material.valueOf(input.toUpperCase(Locale.ROOT));

// 方式 1：完全模仿官方（会顺带做权限检查 + 给玩家发消息）
res.getItemBlacklist().playerListChange(player, mat, false);
res.getItemIgnoreList().playerListChange(player, mat, false);

// 方式 2：自己想控制提示（先自查权限）
boolean allowed = res.getPermissions().hasResidencePermission(player, false)
        || Residence.getInstance().getPlayerManager()
              .getResidencePlayer(player).getGroup().itemListAccess();
if (allowed) {
    boolean added = res.getItemBlacklist().toggle(mat);  // true=已加入黑名单
}

// 读列表（UI 展示）
List<Material> black = Arrays.asList(res.getItemBlacklist().toArray());
boolean banned = res.getItemBlacklist().isListed(Material.STONE);
```

### 坑

- `playerListChange` 打印的文案来自 `Language/Chinese.yml` 的 `General.ListMaterialAdd` / `General.ListMaterialRemove`。
- 黑名单 = "不允许放置"，忽略名单 = "该方块不受 Residence 保护"（语言文件里也是这么写的）。
- `ItemList.toggle()` 是**切换语义**；UI 上要"确定添加/确定移除"就自己判断 `isListed` 再决定调 `add` 还是 `remove`。
- 落盘：`save()` 写进领地的 `BlackList` / `IgnoreList` 节点，但**要等 `ResidenceManager.save()` 或 10 分钟自动保存**。
- `ResidenceItemList` 的构造器是 public，但**不要自己 new** 一个塞回去——领地字段没有 setter。

---

## 4. 进入 / 离开消息

### 结论
存在 `ClaimedResidence.enterMessage` / `leaveMessage` 两个 `protected String` 字段，配 public getter/setter。**没有 `MessageType` 枚举**（6.0.0.1 有个 `containers/ELMessageType`，6.0.3.3 已删除，不要 import）。

### 精确签名

```java
public String getEnterMessage();      // 可能为 null
public String getLeaveMessage();      // 可能为 null
public void setEnterMessage(String message);
public void setLeaveMessage(String message);
public void setEnterLeaveMessage(CommandSender sender, String message, boolean enter, boolean ignorePermissions);
```

### 直接可用（推荐，跳过权限校验）

```java
res.setEnterMessage("&a欢迎来到 &6" + res.getName());
res.setLeaveMessage("&7再见");           // 传 "" 等于清空（官方 /res message remove 就是传空串）
```

### 走官方那条路（会做权限检查 + 发 `Residence_MessageChange`）

```java
// setEnterLeaveMessage 内部：
//  1. message.replace("%subtitle%", "\\n")
//  2. sender 是 Player 时：PermissionGroup.canSetEnterLeaveMessages() 不过且 !ignorePermissions → 发 OwnerNoPermission 并 return
//  3. ResidencePermissions.hasResidencePermission(sender, false) 不过且 !ignorePermissions → General_NoPermission 并 return
//  4. enter ? setEnterMessage(msg) : setLeaveMessage(msg)
//  5. 发 lm.Residence_MessageChange
res.setEnterLeaveMessage(player, "&a欢迎", /*enter*/ true, /*ignorePermissions*/ false);
```

### 坑

1. 传 `null` 会**把消息设成 null**（getter 返回 null）；要清空请传 `""`（官方 remove 就是这么做的）。
2. `setEnterMessage` 只改内存，10 分钟自动保存或 `ResidenceManager.save()` 后才落盘。存档里是 `EnterMessage` / `LeaveMessage` 两个 key。
3. "消息显示在哪里（ActionBar / TitleBar / ChatBox）"**不是**领地属性，是 `config.yml: Messages.GeneralMessages: ActionBar`，属于全局配置，不要在 UI 里做成 per-residence 开关。
4. `config.yml: Messages.ExtraEnterMessage: true` 会在进出时额外插一条"此领地正在出售/出租"的系统提示，你的 UI 如果自己画提示要注意会重复。

---

## 5. 领地内命令拦截（`/res command allow|block|list`）

### 结论
存在**两套**完全不同的结构，别搞混：

| 位置 | 字段 | 访问方式 | 作用 |
|---|---|---|---|
| `ClaimedResidence` | `protected List<String> cmdWhiteList` / `cmdBlackList` | public getter + `addCmdXxxList` | `/res command allow/block` 改的就是它 |
| `FlagPermissions` | `private List<String> CMDWhiteList` / `CMDBlackList` | `getCMDWhiteList()` / `getCMDBlackList()`（**只读**） | 从 config 读的全局命令限制，玩家没有 setter |

### 精确签名

```java
// ClaimedResidence —— /res command 的存储
public List<String> getCmdWhiteList();          // 活引用，可直接 remove
public List<String> getCmdBlackList();
public boolean addCmdWhiteList(String command); // 已存在返回 false；否则小写化+去掉开头的 '/' 后加入，返回 true
public boolean addCmdBlackList(String command);

// FlagPermissions —— 全局命令限制（只读）
public List<String> getCMDWhiteList();
public List<String> getCMDBlackList();
public void parseCommandLimits(ConfigurationSection section);   // 仅插件加载时用
public boolean isInheritCMDLimits();
```

判定逻辑（`ResidencePlayerListener.onCommand` 字节码实测）：只要领地内 `Flags.command == true`，就会拿 `PlayerCommandPreprocessEvent.getMessage()` 去比 `getCmdWhiteList()` / `getCmdBlackList()`，命中黑名单 → `setCancelled(true)`；有白名单且未命中 → 也 cancel。`FlagPermissions` 的那两份名单会额外参与判定。

### 稳定调用示例（复刻 `/res command` 的 toggle）

```java
// 官方逻辑（字节码实测）：
//   allow: if (res.addCmdWhiteList(cmd)) 发 command_addedAllow else 发 command_removedAllow
//   block: if (res.addCmdBlackList(cmd)) 发 command_addedBlock else 发 command_removedBlock
//   list : 遍历 getCmdBlackList()/getCmdWhiteList()，前缀 "/"、把 '_' 换回 ' ' 后拼接展示
ClaimedResidence res = Residence.getInstance().getResidenceManager().getByName(name);

// allow（白名单 toggle）
if (!res.addCmdWhiteList(cmd)) {
    res.getCmdWhiteList().remove(cmd.toLowerCase(Locale.ROOT));   // 已存在 → 移除
}
// block（黑名单 toggle）
if (!res.addCmdBlackList(cmd)) {
    res.getCmdBlackList().remove(cmd.toLowerCase(Locale.ROOT));
}
// 读出来给前端
List<String> blocked = new ArrayList<>(res.getCmdBlackList());
List<String> allowed = new ArrayList<>(res.getCmdWhiteList());
```

### 坑

1. **`addCmdXxxList` 会自动 `toLowerCase()` 并去掉开头的 `/`**，你自己 `remove` 时也要小写化，否则删不掉。
2. `getCmdXxxList()` 返回**活引用**（字段本身），`remove` 是有效的，但没有 `setCmdXxxList()`；要整体替换只能 `clear()` + 逐个 `add`。
3. 带空格的命令用 `_` 连接（官方语言文件："Use _ to include command with multiple variables"）。展示时要自己把 `_` 换回空格。
4. 这两个名单都是 `protected List`，构造时初始化为 `new ArrayList<>()`（两种构造路径都会初始化，不是 null），但**不会**被 `initialize()` 重置。
5. 存档 key：`cmdBlackList` / `cmdWhiteList`（只有非空才写）。落盘同样要等自动保存。
6. 拦截生效的前提是领地 `Flags.command` 为 true；如果服务器把 command flag 默认关了，名单里写什么都没用。

---

## 6. 领地银行

### 结论
`ClaimedResidence.getBank()` → `ResidenceBank`。金额是**领地自己存的钱**（存在领地存档的 `StoredMoney`），不是玩家账户；玩家账户走 `Residence.getEconomyManager()`（`EconomyInterface`，由 Vault / CMIEconomy / Essentials / BlackHole 实现 —— 具体哪个由 `config.yml: Economy.EconomyType` 决定）。

### 精确签名

```java
// ClaimedResidence
public ResidenceBank getBank();

// ResidenceBank（注意 storedMoney / res 是包级可见，字段名里没有 public）
public ResidenceBank(ClaimedResidence res);
public int getStoredMoney();               // double -> intValue()，会截断
public Double getStoredMoneyD();
public String getStoredMoneyFormated();    // 用 EconomyInterface.format() 格式化
public void setStoredMoney(double amount);
public void add(double amount);
public boolean hasEnough(double amount);
public void subtract(double amount);
public void withdraw(CommandSender sender, int amount, boolean ignorePermissions);
public void withdraw(CommandSender sender, double amount, boolean ignorePermissions);
public void deposit(CommandSender sender, int amount, boolean ignorePermissions);
public void deposit(CommandSender sender, double amount, boolean ignorePermissions);
public void showBalance(CommandSender sender, boolean ignorePermissions);

// EconomyInterface（玩家钱包，Vault 等）
public double getBalance(Player p);
public double getBalance(UUID uuid);
public double getBalance(String name);
public boolean canAfford(String name, double amount);
public boolean canAfford(Player p, double amount);
public boolean canAfford(UUID uuid, double amount);
public boolean add(String name, double amount);
public boolean add(UUID uuid, double amount);
public boolean subtract(String name, double amount);
public boolean subtract(Player p, double amount);
public boolean subtract(UUID uuid, double amount);
public boolean transfer(String from, String to, double amount);
public boolean transfer(UUID from, UUID to, double amount);
public String getName();
public String format(double amount);
```

### 稳定调用示例

```java
ResidenceBank bank = res.getBank();

// 读余额（UI 用这个，别用 getStoredMoney() 的 int 截断）
double balance = bank.getStoredMoneyD();
String pretty = bank.getStoredMoneyFormated();

// 存（走官方路径：会检查 Flags.bank + 扣玩家钱包 + 检查 BankCapacity）
bank.deposit(player, 1000.0, /*ignorePermissions*/ false);

// 取（会检查 Flags.bank + 租房人权限）
bank.withdraw(player, 500.0, false);

// 只想读写数字、不要任何权限/消息副作用：
bank.setStoredMoney(bank.getStoredMoneyD() + 100);
bank.add(100); bank.subtract(100);
bank.hasEnough(100);        // boolean
```

### 坑

1. `deposit`/`withdraw` 内部顺序（字节码实测）：`ConfigManager.enableEconomy()` → 玩家 `Flags.bank` 用 `FlagCombo.OnlyTrue` 判定（`ResidencePermissions.playerHas(player, Flags.bank, FlagPermissions.FlagCombo.OnlyTrue)`）→ `EconomyInterface.canAfford` → `ConfigManager.BankCapacity`（**public double 字段**）→ 真正的转账。
2. `getStoredMoney()` 返回 `int`（`Double.intValue()` 截断），金额大时不要用它做运算。
3. `Residence.getInstance().getEconomyManager()` 在 `EnableEconomy: false` 或没有任何经济插件时**可能是 `BlackHoleEconomy`**，所有操作静默失败/返回 0。要先判 `ConfigManager.enableEconomy()`。
4. 银行相关 flag 名是 `Flags.bank`（`com.bekvon.bukkit.residence.containers.Flags.bank`，public static final 字段，不是枚举 `.valueOf`）。
5. `claim.getBank()` 永不为 null（`initialize()` 里 `new ResidenceBank(this)`），但**无参构造 `new ClaimedResidence()` 不调用 `initialize()`** —— 永远不要自己 new ClaimedResidence。

---

## 7. 市场：出售 / 出租 / 租约

### 7.1 出售（sell / unsell / buy）

```java
// TransactionManager implements MarketBuyInterface
public void putForSale(String resName, Player player, int amount, boolean ignorePermissions);
public void putForSale(ClaimedResidence res, Player player, int amount, boolean ignorePermissions);
public boolean putForSale(String resName, int amount);                 // 无权限检查版
public boolean putForSale(ClaimedResidence res, int amount);
public void buyPlot(String resName, Player player, boolean ignorePermissions);
public void buyPlot(ClaimedResidence res, Player player, boolean ignorePermissions);
public void removeFromSale(Player player, String resName, boolean ignorePermissions);   // = unsell
public void removeFromSale(Player player, ClaimedResidence res, boolean ignorePermissions);
public void removeFromSale(String resName);
public void removeFromSale(ClaimedResidence res);
public void removeFromSale(ClaimedResidence res, boolean removeSign);
public boolean isForSale(String resName);
public boolean isForSale(ClaimedResidence res);
public boolean viewSaleInfo(String resName, Player player);
public boolean viewSaleInfo(ClaimedResidence res, Player player);
public void printForSaleResidences(Player player, int page);
public int getSaleAmount(ClaimedResidence res);
public int getSaleAmount(String resName);
public Map<String, Integer> getBuyableResidences();
public void clearSales();
```

**"正在出售"怎么读**：`ClaimedResidence.isForSell()` 内部就是 `TransactionManager.isForSale(this.getName())`；售价用 `TransactionManager.getSaleAmount(res)`（它内部读 `ClaimedResidence.getSellPrice()`），或直接 `res.getSellPrice()`（`Integer`）/ `res.setSellPrice(Integer)` / `res.getBlockSellPrice()`。

**坑**：

- `isForSell()` 查的是 `TransactionManager` 的 `sellAmount` 集合，**不是** `sellPrice` 字段；必须用 `putForSale(...)` 上架，光 `setSellPrice` 不会上架。
- `putForSale(ClaimedResidence, int)` 在 `enabledRentSystem()` 为 true 时，如果领地 `isForRent()` / `isSubzoneForRent()` / `isParentForRent()` 有任意一个为 true → **直接返回 false**（出售和出租互斥）。
- `config.yml: Economy.Sell.Subzone: false` → 子领地默认不能出售；官方 `/res market` 里也会挡。桥接要自己判断 `isSubzone()`。
- `removeFromSale(res)` 会顺带 `SignUtil.removeSign(res)` 清理告示牌。

### 7.2 出租（rentable / rent / unrent / payrent / autopay）

```java
// RentManager implements MarketRentInterface（6.0.0.1 与 6.0.3.3 完全一致，逐行 diff 过）
public void setForRent(Player player, String resName, int days, int cost, boolean allowRenewing, boolean stayInMarket);
public void setForRent(Player player, String resName, int days, int cost, boolean allowRenewing, boolean stayInMarket, boolean allowAutoPay);
public void setForRent(Player player, String resName, int days, int cost, boolean allowRenewing, boolean stayInMarket, boolean allowAutoPay, boolean ignorePermissions);
public void setForRent(Player player, ClaimedResidence res, int days, int cost, boolean allowRenewing, boolean stayInMarket, boolean allowAutoPay, boolean ignorePermissions);

public void rent(Player player, String resName, boolean ignorePermissions, boolean ignoreLimits);   // = 租下
public void rent(Player player, ClaimedResidence res, boolean ignorePermissions, boolean ignoreLimits);
public void payRent(Player player, String resName, boolean ignorePermissions);                      // = payrent
public void payRent(Player player, ClaimedResidence res, boolean ignorePermissions);
public void unrent(Player player, String resName, boolean ignorePermissions);                       // = unrent / 退租
public void unrent(Player player, ClaimedResidence res, boolean ignorePermissions);
public void removeFromForRent(Player player, String resName, boolean ignorePermissions);            // 撤下出租
public void removeFromForRent(Player player, ClaimedResidence res, boolean ignorePermissions);
public void removeFromRent(String resName);
public void removeFromRent(ClaimedResidence res);
public void removeRentable(String resName);
public void removeRentable(ClaimedResidence res);
public void removeRentable(ClaimedResidence res, boolean removeSign);

// 读状态
public boolean isForRent(String resName);
public boolean isForRent(ClaimedResidence res);
public boolean isRented(String resName);
public boolean isRented(ClaimedResidence res);
public RentableLand getRentableLand(String resName);
public RentableLand getRentableLand(ClaimedResidence res);      // 内部用 res.getRentable()，未出租返回 null
public RentedLand getRentedLand(String resName);
public RentedLand getRentedLand(ClaimedResidence res);          // 内部用 res.getRentedLand()
public String getRentingPlayer(String resName);
public String getRentingPlayer(ClaimedResidence res);
public int getCostOfRent(String resName);
public int getCostOfRent(ClaimedResidence res);
public int getRentDays(String resName);
public int getRentDays(ClaimedResidence res);
public boolean getRentableRepeatable(String resName);           // = 是否允许续租
public boolean getRentableRepeatable(ClaimedResidence res);
public boolean getRentedAutoRepeats(String resName);            // = 当前租客是否开了自动续租
public boolean getRentedAutoRepeats(ClaimedResidence res);
public void setRentRepeatable(Player player, ClaimedResidence res, boolean repeatable, boolean ignorePermissions);
public void setRentedRepeatable(Player player, ClaimedResidence res, boolean repeatable, boolean ignorePermissions);
public Set<ClaimedResidence> getRentableResidences();
public Set<ClaimedResidence> getCurrentlyRentedResidences();
public List<ClaimedResidence> getRentedLands(UUID uuid);
public List<ClaimedResidence> getRentedLands(UUID uuid, boolean includeSubzones);
public List<ClaimedResidence> getRentedLands(UUID uuid, boolean includeSubzones, World world);
public List<ClaimedResidence> getRents(String playerName);
public List<ClaimedResidence> getRents(String playerName, boolean includeSubzones, World world);
public TreeMap<String, ClaimedResidence> getRentsMap(UUID uuid, boolean includeSubzones, World world);
public int getRentCount(UUID uuid);
public int getRentableCount(UUID uuid);
public void printRentInfo(Player player, ClaimedResidence res);
public void checkCurrentRents();
```

**两个数据容器（字段全是 public，javap 实测）**：

```java
com.bekvon.bukkit.residence.economy.rent.RentableLand
    public int days;
    public int cost;
    public boolean AllowRenewing;
    public boolean StayInMarket;
    public boolean AllowAutoPay;
    public Map<String, Object> save();

com.bekvon.bukkit.residence.economy.rent.RentedLand
    public String player;
    public long startTime;
    public long endTime;
    public boolean AutoPay;
    // private UUID uuid  —— 用 getUniqueId() / setUniqueId(UUID)
    public boolean hasValidRenter();
    public boolean isRenter(CommandSender sender);
    public boolean isRenter(Player player);
    public boolean isRenter(UUID uuid);
    public boolean isRenter(String name);
    public String getRenterName();
    public UUID getUniqueId();
    public void setUniqueId(UUID uuid);
    public static RentedLand loadRented(Map<String, Object> map);
```

**ClaimedResidence 上的市场状态方法（全实测）**：

```java
public boolean isForSell();
public boolean isForRent();
public boolean isRented();
public boolean isSubzoneForRent();     // 任一子领地正在出租
public boolean isSubzoneRented();      // 任一子领地被租走
public ClaimedResidence getRentedSubzone();
public boolean isParentForRent();
public boolean isParentForSell();
public void setRentable(RentableLand rentable);
public RentableLand getRentable();
public void setRented(RentedLand rented);
public RentedLand getRentedLand();
public Integer getSellPrice();
public void setSellPrice(Integer price);
```

### 稳定调用示例

```java
RentManager rm = Residence.getInstance().getRentManager();

// 上架出租：days=租期天数, cost=租金, allowRenewing=允许续租, stayInMarket=到期后留在市场
rm.setForRent(player, res, 7, 5000, true, true, false, false);
//                                                      ↑autopay ↑ignorePerms

// 租下 / 退租 / 付租
rm.rent(player, res, false, false);
rm.unrent(player, res, false);
rm.payRent(player, res, false);
rm.removeFromForRent(player, res, false);      // = /res market unrent（房东撤租）

// 自动续租：官方 /res market autopay 走的是 config 默认 + 玩家对 RentedLand.AutoPay 的选择
RentedLand rented = res.getRentedLand();
if (rented != null) {
    boolean autoPay = rented.AutoPay;
    long endTime = rented.endTime;
    UUID renter = rented.getUniqueId();
}
// 注意：没有 setAutoPay(...) 这种 API，只有 public 字段 AutoPay / 或走 setRentedRepeatable(...)

// 前端展示
boolean forRent = res.isForRent();
RentableLand info = res.getRentable();          // 可为 null
int days  = info != null ? info.days : 0;
int cost  = info != null ? info.cost : 0;
boolean renewing = info != null && info.AllowRenewing;
boolean autoPay = info != null && info.AllowAutoPay;
```

### 坑

1. `setForRent` 有 4 个重载，**参数顺序容易记错**：`(player, res, days, cost, allowRenewing, stayInMarket, allowAutoPay, ignorePermissions)`。用 8 参数的 ClaimedResidence 版本最安全。
2. `getRentable()` / `getRentedLand()` 可能为 `null`；`getRentableLand(res)` 在 `isForRent()` 为 false 时**必然返回 null**。
3. `getRentingPlayer()` 在没租出去时返回 `null`。
4. 自动续租的开关语义是"两个开关相乘"：领地侧 `RentableLand.AllowAutoPay` + 租客侧 `RentedLand.AutoPay`；要改租客侧，用 `setRentedRepeatable(player, res, repeatable, ignorePermissions)`，它内部会写 `RentedLand.AutoPay`。
5. **出租中不能出售**、**有子领地在出租时父领地不能删**（`ConfigManager.isRentPreventRemoval()`）。
6. `rent(...)` 最后一个参数是 `ignoreLimits`（绕过 `maxRents`/`maxRentables`/`maxRentDays` 检查），给玩家用请传 `false`。

### 7.3 租约（Lease，另一套东西，别和 rent 混）

`LeaseManager` 是"领地限时使用"（`/res lease`），不是市场出租。

```java
public boolean leaseExpires(ClaimedResidence res);
public boolean leaseExpires(String resName);
public boolean isLeased(ClaimedResidence res);           // 内部：res.getLeaseExpireTime() > 0
public String getExpireTime(ClaimedResidence res);
public String getExpireTime(String resName);
public void removeExpireTime(ClaimedResidence res);
public void removeExpireTime(String resName);
public void setExpireTime(Player player, ClaimedResidence res, int days);
public void setExpireTime(String resName, int days);
public void setExpireTime(Player player, String resName, int days);
public void renewArea(String resName, Player player);
public void renewArea(ClaimedResidence res, Player player);
public double getRenewCostD(ClaimedResidence res);
public void doExpirations();
public void resetLeases();
public Map<String, Long> save();
public void load(Map<String, Object> map);               // 6.0.3.3 是 void！6.0.0.1 返回 LeaseManager
```

`ClaimedResidence` 侧：`public Long getLeaseExpireTime();` / `public void setLeaseExpireTime(long);`

**没有 `getLeaseDays()` / `setLeaseDays()` 这两个方法**（javap 里不存在）。要"从今天起租 N 天"就 `setExpireTime(player, res, days)`；`days` 上限是 `PermissionGroup.getMaxLeaseTime()`。清空租约用 `removeExpireTime(res)`（`setExpireTime(..., 0)` 语义不等价，别赌）。

---

## 8. 转让 / 重命名 / 扩大缩小 / 删除

### 8.1 转让 / 改所有者

```java
// ClaimedResidence
public boolean isOwner(String name);
public boolean isOwner(UUID uuid);
public boolean isOwner(Player player);
public boolean isOwner(CommandSender sender);
public String getOwner();
public UUID getOwnerUUID();

// ResidencePermissions —— 真正的 setOwner 在这里（ClaimedResidence 上没有 setOwner！）
public boolean setOwner(Player player, boolean applyDefaultFlags);
public boolean setOwner(UUID uuid, boolean applyDefaultFlags);
public void setOwner(String name, boolean applyDefaultFlags);
public void setOwnerUUID(UUID uuid);
public String getOwner();
public UUID getOwnerUUID();
public String getOwnerLastKnownName();
public void setOwnerLastKnownName(String name);

// ResidenceManager —— 官方 /resadmin setowner、/res give 走这里
public void giveResidence(Player sender, String targetName, String resName, boolean ignorePermissions);
public void giveResidence(Player sender, String targetName, String resName, boolean ignorePermissions, boolean keepFlags);
public void giveResidence(Player sender, String targetName, ClaimedResidence res, boolean ignorePermissions, boolean keepFlags);
```

**关键语义（字节码实测，和名字相反，务必注意）**：

- `ResidencePermissions.setOwner(UUID, boolean)` 里的 boolean 是 **`applyDefaultFlags`**：
  - 传 `true` → 调 `applyDefaultFlags()`，**新主人的默认 flag 生效，原 flag 被覆盖**（= `/resadmin setowner` 不带 `-keepflags`）。
  - 传 `false` → **保留原 flag**（= `/resadmin setowner -keepflags`）。
- `setOwner` 会 `callEvent(new ResidenceOwnerChangeEvent(...))`，**可被其他插件取消**，取消时返回 `false`（`setOwner(String, boolean)` 是 void，取消就静默 return）。所以要判断是否成功，用 `Player`/`UUID` 版本看返回值。
- `setOwner(UUID, boolean)` 内部会：`PlayerManager.removeResFromPlayer(oldUUID, res)` → `addResidence(newUUID, res)` → 更新 `ownerLastKnownName` / `ownerUUID`。**不会**处理出租/出售/告示牌。

**官方 `/resadmin setowner` 的完整动作（字节码实测顺序）**，建议照抄：

```java
// /resadmin setowner [residence] [player] (-keepflags)
boolean keepFlags = args 里出现 "-keepflags";
if (!res.getRaid().isRaidInitialized()) {              // 被 raid 时禁止
    if (PlayerManager.isPlayerExist(sender, targetName, false)) {
        res.getPermissions().setOwner(targetName, !keepFlags);   // 注意取反！
        if (Residence.getInstance().getRentManager().isForRent(res))
            Residence.getInstance().getRentManager().removeRentable(res);
        if (Residence.getInstance().getTransactionManager().isForSale(res))
            Residence.getInstance().getTransactionManager().removeFromSale(res);
        res.getPermissions().applyDefaultFlags();          // ← 官方无条件又调了一次！
        Residence.getInstance().getSignUtil().updateSignResName(res);
    }
}
// 父/子领地提示：getParent() != null → Subzone_OwnerChange，否则 Residence_OwnerChange
```

⚠️ 官方无条件调了 `applyDefaultFlags()`，所以走官方路径 flag 一定会被重置。你的 UI 想"保留 flag 转让"，就**不要**用 `/resadmin setowner` 的路径，直接 `perms.setOwner(newUUID, false)`。

### 8.2 重命名

```java
// 改领地名（/res rename）
public boolean renameResidence(String oldName, String newName);
public boolean renameResidence(Player player, String oldName, String newName, boolean ignorePermissions);
public boolean renameResidence(CommandSender sender, String oldName, String newName, boolean ignorePermissions);
public boolean renameResidence(CommandSender sender, ClaimedResidence res, String newName, boolean ignorePermissions);  // 仅 6.0.3.3，慎用

// 改区域名 / 子领地名
public boolean renameArea(String oldName, String newName);
public boolean renameArea(Player player, String oldName, String newName, boolean ignoreY);
public boolean renameSubzone(String oldName, String newName);
public boolean renameSubzone(Player player, String oldName, String newName, boolean ignoreY);
public boolean renameSubzone(CommandSender sender, String oldName, String newName, boolean ignoreY);
```

`renameResidence` 内部会检查：`ResPerm.rename` 权限 → `Residence.validName(newName)` → 目标名已存在 → **raid 中禁止**。失败只返回 `false` + 给 sender 发语言消息，没有异常信息。

⚠️ `renameResidence` 用的是**全名查找**（`getByName`），改名子领地要用 `renameSubzone`，不要拿全路径去调 `renameResidence`。

### 8.3 扩大 / 缩小

**官方 `/res expand` / `/res contract` 的真实实现（字节码一致，抄这个最稳）**：

```java
// 1) 取出要改的区域
String areaName = res.isSubzone()
        ? Residence.getInstance().getResidenceManager().getSubzoneNameByRes(res)   // 子领地
        : res.getAreaIDbyLoc(player.getLocation());                                 // 玩家所在地的区域 key
CuboidArea area = res.getArea(areaName);
if (area == null) area = res.getCuboidAreabyName("main");                           // 兜底

// 2) 把玩家的选区设成这个区域，然后按玩家朝向改选区
SelectionManager sel = Residence.getInstance().getSelectionManager();
sel.placeLoc1(player, area.getHighLocation(), true);
sel.placeLoc2(player, area.getLowLocation(), true);
// 注意官方顺序是 placeLoc1(high) / placeLoc2(low)，不要反过来（getSelectionCuboid 内部会重新排序取 min/max）

// 3) 扩 / 缩
sel.modify(player, /*expand*/ true, amount);     // expand（识别 player 朝向 getDirection(player)）
// 或
sel.contract(player, amount);                     // contract（内部同样按朝向，把选区往内收）

// 4) 用新选区替换区域
if (sel.hasPlacedBoth(player)) {
    res.replaceArea(player, sel.getSelectionCuboid(player), areaName, false);
}
```

⚠️ 潜在坑：`SelectionManager.modify(player, ...)` 里 `getDirection(player)` 可能返回 null，官方会发 `Invalid_Direction` 并 return，此时选区未变。**调用后一定要 `hasPlacedBoth` + 尺寸比对，不要假设 modify 成功。**

更可控的做法（你自己知道目标坐标，不依赖朝向）：

```java
CuboidArea old = res.getArea("main");
Location low  = old.getLowLocation().clone().add(-dx, 0, -dz);
Location high = old.getHighLocation().clone().add(dx, 0, dz);
CuboidArea neu = new CuboidArea(low, high);                 // public CuboidArea(Location, Location)
boolean ok = res.replaceArea(player, neu, "main", /*ignoreY*/ false);
// 只想静默改（不做权限/限额检查）：res.replaceArea(neu, "main")
```

**没有** `expand(int)` / `contract(int)` 这种直接方法（javap 里不存在），必须走"选区 + replaceArea"。

### 8.4 删除领地

```java
public void removeResidence(ClaimedResidence res);
public void removeResidence(String resName);
public void removeResidence(CommandSender sender, ClaimedResidence res, boolean ignorePermissions);
public void removeResidence(CommandSender sender, String resName, boolean ignorePermissions);
public void removeResidence(Player player, ClaimedResidence res, boolean ignorePermissions);
public void removeResidence(Player player, String resName, boolean ignorePermissions);
public void removeResidence(ResidencePlayer rplayer, ClaimedResidence res, boolean ignorePermissions);
public void removeResidence(ResidencePlayer rplayer, ClaimedResidence res, boolean ignorePermissions, boolean keepSubzones);

public void removeAllByOwner(String ownerName);
public void removeAllFromWorld(CommandSender sender, String worldName);
public void removeAllFromWorld(CommandSender sender, String worldName, List<String> exclude);
```

**怎么选**：

- **插件内部删除（推荐）**：`Residence.getInstance().getResidenceManager().removeResidence(res);`
  —— 无权限检查，`callEvent(new ResidenceDeleteEvent(null, res, DeleteCause.OTHER))`，若被取消则中止。会连带处理租约/出租/告示牌/玩家索引。
- **代玩家执行删除（要复刻 `/res remove` 的权限语义）**：`removeResidence(player, res, false)`。
  `removeResidence(CommandSender, ...)` 在 sender 不是 Player 时会**转成 `removeResidence(null, res, true, false)`**（`ignorePermissions = true`）——从控制台/后台调用会**绕过权限**，桥接里一定要注意别用错重载。
- `removeResidence(ResidencePlayer, ClaimedResidence, boolean, boolean)` 的最后一个 boolean 是 `keepSubzones`（**仅这个 8 参数/4 参数重载有**，6.0.0.1 与 6.0.3.3 一致）。

官方 `/res remove` 自己的前置检查（字节码实测，建议桥接照抄做 UI 提示）：

```
res.isSubzone() ? ResPerm.delete_subzone 权限 : (isOwner ? ResPerm.delete : ResPerm.admin)
+ 子领地额外：PreventSubZoneRemoval 时要求 player 是 parent 的 owner 或 parent.permissions.playerHas(player, Flags.admin, OnlyTrue)
+ res.getRaid().isRaidInitialized() → 禁止删除（Raid_noRemoval）
+ Residence.deleteConfirm map（两级确认）
```

**子领地删除用 `child.removeSubzone(name)`（在父上调用），不要用 `removeResidence`** —— 虽然 `removeResidence` 也能删子领地，但 `removeSubzone` 会正确处理父的 `subzones` map 与缓存。

---

## 9. 领地聊天 rc

### 结论
`/res rc` = "切换我在哪个聊天频道"，频道对象是 `ChatChannel`（每个领地对一个），成员存 `Set<UUID>`，前缀/颜色存在领地与频道对象上。**没有广播消息的公开 API 之外的复杂结构**。

### 精确签名

```java
// Residence
public ChatManager getChatManager();
public com.bekvon.bukkit.residence.api.ChatInterface getResidenceChatAPI();

// ChatManager implements ChatInterface
public boolean setChannel(UUID uuid, String channelName);
public boolean setChannel(UUID uuid, ClaimedResidence res);
public boolean removeFromChannel(UUID uuid);
public ChatChannel getChannel(String channelName);        // 不存在返回 null
public ChatChannel getPlayerChannel(UUID uuid);           // 不在频道返回 null
public static void tooglePlayerResidenceChat(Player player, String resName);   // 注意官方拼写就是 toogle
public static void removePlayerResidenceChat(Player player);
public static void removePlayerResidenceChat(UUID uuid);

// ChatChannel
public ChatChannel(String channelName, String chatPrefix, CMIChatColor color);
public String getChannelName();
public void setChatPrefix(String prefix);
public void setChannelColor(CMIChatColor color);
public void chat(String playerName, String message);       // 发消息（内部 callEvent(ResidenceChatEvent)）
public void join(UUID uuid);
public void leave(UUID uuid);
public boolean hasMember(UUID uuid);
public int memberCount();

// ClaimedResidence
public ChatChannel getChatChannel();                       // 注意返回类型是 net.Zrips.CMILib 之外的：ChatChannel
public String getChatPrefix();
public void setChatPrefix(String prefix);
public CMIChatColor getChannelColor();
public void setChannelColor(CMIChatColor color);
```

### 稳定调用示例（复刻 `/res rc` 的 toggle）

```java
// 官方 rc 分支（字节码实测）：
//   1) ConfigManager.chatEnabled() == false → 发 Residence_ChatDisabled，return
//   2) res == null（不在领地内）→ 发 Residence_NotIn，return
//   3) 已在同频道 → chatManager.removeFromChannel(uuid) + ChatManager.removePlayerResidenceChat(player)
//   4) 未在频道  → 检查 res.getPermissions().playerHas(player.getName(), Flags.chat, false)
//                  或 PermissionManager.isResidenceAdmin(player)
//                  然后 ChatManager.tooglePlayerResidenceChat(player, res.getName())
Residence rs = Residence.getInstance();
if (!rs.getConfigManager().chatEnabled()) { /* 聊天频道已关 */ }
ChatManager cm = rs.getChatManager();
ChatChannel cur = cm.getPlayerChannel(player.getUniqueId());
boolean inThis = cur != null && cur.getChannelName().equals(res.getName());
if (inThis) {
    cm.removeFromChannel(player.getUniqueId());
    ChatManager.removePlayerResidenceChat(player);
} else {
    ChatManager.tooglePlayerResidenceChat(player, res.getName());
}

// 读频道信息给前端
ChatChannel ch = res.getChatChannel();
String prefix = res.getChatPrefix();                     // "" 表示没设
CMIChatColor color = res.getChannelColor();              // 默认 CMIChatColor.WHITE
if (ch != null) {
    int members = ch.memberCount();
    boolean inside = ch.hasMember(player.getUniqueId());
    String chName = ch.getChannelName();
}
```

### 坑

1. `setChatPrefix` / `setChannelColor` 的参数类型是 `net.Zrips.CMILib.Colors.CMIChatColor`（CMILib 的类，**不在 Residence jar 里**）。桥接要写这两个 API，编译期必须有 CMILib 依赖，否则只能转发命令 `/res set ...` / `/res rc`。运行时 CMILib 一定在（Residence 硬依赖它，`D:\1.21.11\plugins\` 下应有 CMILib）。
2. 频道成员 `Set<UUID> members` 是 `protected`，只能通过 `join/leave/hasMember/memberCount` 操作，**拿不到完整成员列表**。要在 UI 里列成员，得遍历在线玩家逐个 `hasMember`，或者自己维护。
3. `ChatChannel.chat(...)` 内部走 `CMIScheduler.runTask`（主线程），而且会 `callEvent(ResidenceChatEvent)`（可取消）。
4. 全局相关配置：`config.yml: ResidenceChatEnable: true`、`ResidenceChatColor: DARK_PURPLE`（这里是 `ConfigManager.getChatColor()`，是 **String→CMIChatColor** 解析后的结果）、`ResidenceChatListening: false`、`ResidenceChatPrefixLength: 16`（**前缀长度上限**，UI 输入要截断）。

---

## 10. 杂项：`/res current`、`/res limits`、`/res unstuck`

### `/res current` —— 完全可以纯 API

```java
// 官方实现就三行（字节码实测）
ClaimedResidence res = Residence.getInstance().getResidenceManager().getByLoc(player.getLocation());
// res == null → 发 lm.Residence_NotIn；否则发 lm.Residence_In + res.getName()
```

即：`ResidenceManager.getByLoc(Location)` 是唯一需要的东西，返回 `ClaimedResidence`（可能 null）。它**返回的是最深层**的领地/子领地（内部按 chunk 表 + 最深的那个）。

### `/res limits` —— 纯 API，不需要转发命令

```java
// 官方实现（字节码实测）：
//   权限：ResPerm.command_$1_others（查别人时）
//   Residence.getPlayerManager().getResidencePlayer(uuid).getGroup().printLimits(sender, uuid, true)
PermissionGroup pg = Residence.getInstance().getPlayerManager()
        .getResidencePlayer(player).getGroup();
pg.printLimits(player, player.getUniqueId(), /*sendMessage*/ true);
```

要**自己画 UI**（不打语言文件）就用这些 getter：

```java
// ResidencePlayer（6.0.3.3 实测）
public int getMaxRes();
public int getMaxRents();
public int getMaxSubzones();
public int getMaxSubzoneDepth();
public int getMaxX();
public int getMaxZ();
public int getResAmount();
public List<ClaimedResidence> getResList();
public PermissionGroup getGroup();
public PermissionGroup forceUpdateGroup();

// PermissionGroup
public String getGroupName();
public int getMaxX();  public int getMaxYSize();  public int getMaxY();  public int getMaxZ();
public int getSubzoneMaxX(); public int getSubzoneMaxY(); public int getSubzoneMaxZ();
public int getMaxHeight();
public int getMaxZones();
public int getMaxSubzoneDepth();
public int getMaxSubzones();
public int getMaxLeaseTime();
public int getMaxRents();
public int getMaxRentDays();
public int getMaxRentables();
public int getMaxPhysicalPerResidence();
public boolean canSetEnterLeaveMessages();
public boolean buyLandIgnoreLimits();
public boolean hasUnstuckAccess();
public boolean itemListAccess();
public void printLimits(CommandSender sender, UUID uuid, boolean sendMessage);
public void printLimits(CommandSender sender, OfflinePlayer p, boolean sendMessage);

// 静态便捷（仅 6.0.3.3，用了就必须在 6.0.3.3 上跑）
public static PermissionGroup PermissionGroup.getGroup(Player player);
public static PermissionGroup PermissionGroup.getGroup(UUID uuid);
public static PermissionGroup PermissionGroup.getGroup(UUID uuid, String worldName);

// 玩家个别加成（覆盖 group 上限）
com.bekvon.bukkit.residence.containers.ResidencePlayerMaxValues.get(UUID uuid)
    .getMaxRes() / getMaxRents() / getMaxSubzones() / getMaxSubzoneDepth() / getMaxX() / getMaxZ()
```

⚠️ 用 `pg.getMaxX()` 等要**先确认 group 非 null**；用 6.0.0.1 编译时**不能**用 `PermissionGroup.getGroup(...)` 静态方法（那边没有），要用 `Residence.getInstance().getPlayerManager().getResidencePlayer(uuid).getGroup()`。

### `/res unstuck` —— 可以用 API，但语义是"踢出领地"

```java
// 官方实现（字节码实测）：
//   1) 权限：PermissionGroup.hasUnstuckAccess() || ResAdmin.isResAdmin(player)，否则 General_NoPermission
//   2) res = ResidenceManager.getByLoc(player.getLocation())；null → Residence_NotIn
//   3) res.kickFromResidence(player)
public boolean kickFromResidence(Player player);   // ClaimedResidence
```

**注意**：`kickFromResidence` 内部是把玩家**传送到 `ConfigManager.getKickLocation()`** 并 `closeInventory()`（字节码实测调用了 `player.closeInventory()`），不是"随机传送到安全点"。随机传送是另一套：`Residence.getRandomTpManager()`（`com.bekvon.bukkit.residence.utils.RandomTp`，对应 `/res rt`）。

```java
// 稳定调用
if (res != null && (hasUnstuckAccess || ResAdmin.isResAdmin(player))) {
    res.kickFromResidence(player);
}
```

---

## 11. 所有权与权限判断（常用清单）

```java
// —— 所有权（ClaimedResidence）
public boolean isOwner(String playerName);
public boolean isOwner(UUID uuid);
public boolean isOwner(Player player);
public boolean isOwner(CommandSender sender);
public String getOwner();
public UUID getOwnerUUID();
public boolean isTrusted(Player player);
public boolean isTrusted(String playerName);
public boolean isTrusted(UUID uuid);
public boolean isTrusted(ResidencePlayer rplayer);
public Set<ResidencePlayer> getTrustedPlayers();
public ResidencePlayer getRPlayer();
public PermissionGroup getOwnerGroup();
public ArrayList<Player> getPlayersInResidence();

// —— 权限（FlagPermissions / ResidencePermissions）
public boolean has(Flags flag, boolean def);
public boolean has(Flags flag, boolean def, boolean ignoreParent);
public boolean has(Flags flag, FlagPermissions.FlagCombo combo);
public boolean has(String flag, boolean def);
public boolean has(String flag, boolean def, boolean ignoreParent);
public boolean playerHas(Player player, Flags flag, boolean def);
public boolean playerHas(Player player, Flags flag, FlagPermissions.FlagCombo combo);
public boolean playerHas(String playerName, Flags flag, boolean def);
public boolean playerHas(UUID uuid, String playerName, Flags flag, boolean def);
public boolean groupHas(String groupName, String flag, boolean def);
public boolean isSet(String flag);
public boolean isPlayerSet(String playerName, String flag);
public Map<String, Boolean> getFlags();
public Map<String, Boolean> getPlayerFlags(UUID uuid);
public Map<String, Boolean> getPlayerFlags(String playerName);
public List<String> getPossibleFlags(Player player, boolean onlySet, boolean onlyPlayerFlags);

// ResidencePermissions 特有（sender 带权限校验的 setter）
public boolean hasResidencePermission(CommandSender sender, boolean checkAdmin);
public boolean setFlag(CommandSender sender, String flag, FlagPermissions.FlagState state, boolean ignorePermissions);
public boolean setFlag(String flag, FlagPermissions.FlagState state);
public boolean setPlayerFlag(CommandSender sender, String playerName, String flag, boolean value, boolean ignorePermissions, boolean ignoreNonAdmin);
public boolean setPlayerFlag(UUID uuid, String flag, FlagPermissions.FlagState state);
public boolean setGroupFlag(String groupName, String flag, FlagPermissions.FlagState state);
public void removeAllPlayerFlags(CommandSender sender, String playerName, boolean ignorePermissions);
public void applyDefaultFlags();
public void applyDefaultFlags(Player player, boolean ignorePermissions);
public void applyDefaultRentedFlags();
public void applyGlobalDefaults();
public void applyTemplate(Player player, FlagPermissions other, boolean applyToPlayer);
public static boolean isEventCallsSuspended();
public static void setEventCallsSuspended(boolean suspended);   // 批量改 flag 时抑制事件用

// 静态便捷
public static boolean FlagPermissions.has(Location loc, Flags flag, boolean def);
public static boolean FlagPermissions.has(Location loc, Flags flag, FlagPermissions.FlagCombo combo);
public static boolean FlagPermissions.has(Location loc, Player player, Flags flag, boolean def);
public static FlagPermissions FlagPermissions.getPerms(Location loc);
public static FlagPermissions FlagPermissions.getPerms(Player player);
public static FlagPermissions FlagPermissions.getPerms(Location loc, Player player);
public static FlagPermissions FlagPermissions.getPerms(World world);
// Residence 上的两个实例方法
public FlagPermissions getPermsByLoc(Location loc);
public FlagPermissions getPermsByLocForPlayer(Location loc, Player player);

// —— 管理员判定（三种，全实测）
Residence.getInstance().isResAdminOn(Player)                  // boolean
Residence.getInstance().getPermissionManager().isResidenceAdmin(CommandSender)   // boolean
com.bekvon.bukkit.residence.containers.ResAdmin.isResAdmin(CommandSender|Player|UUID)  // static
```

枚举常量：

```
FlagPermissions.FlagState  = { TRUE, FALSE, NEITHER, INVALID }        // NEITHER = 不设置（移除）
FlagPermissions.FlagCombo  = { OnlyTrue, OnlyFalse, TrueOrNone, FalseOrNone }
Flags（public static final 字段，不是 valueOf）= admin, build, use, container, tp, move, chat, bank,
     subzone, command, commandblock, pvp, animalkilling, ...
```

---

## 附 A. "只能转发命令"的部分（明确结论）

| 功能 | 能否纯 API | 说明 |
|---|---|---|
| `/res current` | ✅ | `getByLoc(player.getLocation())` |
| `/res limits` | ✅ | `ResidencePlayer.getGroup().printLimits(...)` 或直接读 getter |
| `/res unstuck` | ✅ | `res.kickFromResidence(player)`（等价踢出到 kick 点） |
| `/res subzone` | ✅ | `parent.addSubzone(player, loc1, loc2, name, ignoreY)` |
| `/res lset` | ✅ | `res.getItemBlacklist()/getItemIgnoreList()` + `playerListChange/toggle` |
| `/res command allow\|block` | ✅ | `addCmdWhiteList/addCmdBlackList` + `getCmdXxxList().remove(...)` |
| `/res bank` | ✅ | `res.getBank().deposit/withdraw/showBalance` |
| `/res market sell/unsell/buy` | ✅ | `TransactionManager.putForSale/removeFromSale/buyPlot` |
| `/res market rentable/rent/unrent/payrent` | ✅ | `RentManager.setForRent/rent/unrent/payRent` |
| `/res market autopay` | ⚠️ 半 | 读：`RentedLand.AutoPay`；写：只有 `setRentedRepeatable(...)`，**没有** `setAutoPay(...)` |
| `/res lease` | ✅ | `LeaseManager.setExpireTime/removeExpireTime/renewArea`；**没有 getLeaseDays/setLeaseDays** |
| `/res message` | ✅ | `setEnterMessage/setLeaveMessage`（清空传 `""`） |
| `/res rc` | ✅ | `ChatManager` 静态 toggle + `getPlayerChannel/hasMember/memberCount` |
| **设置聊天前缀/颜色** | ⚠️ | 需要 `net.Zrips.CMILib.Colors.CMIChatColor` 类型，无 CMILib 依赖时只能转发 `/res set` |
| **`/res set`（改 flag）** | ✅ | `ResidencePermissions.setFlag(sender, name, FlagState, ignorePermissions)` |
| `/res expand` / `contract` | ✅ | `SelectionManager.placeLoc1/2` → `modify/contract` → `replaceArea` |
| `/res give` / `setowner` | ✅ | `ResidencePermissions.setOwner(uuid, applyDefaultFlags)`（注意 boolean 反向语义） |
| **`/res rt`（随机传送）** | ⚠️ 不确定 | `Residence.getRandomTpManager()` 返回 `utils.RandomTp`；本次没逐个核对它的方法签名，用前请先 `javap -p com.bekvon.bukkit.residence.utils.RandomTp` |
| **领地备份/恢复（`/res backup`）** | ❌ 未找到 | 6.0.3.3 里有 `selection/WESchematicManager`、`Schematics7Manager`、`utils/FileCleanUp`，但本次未确认存在"备份/还原"的公开入口；不确定，需要单独查 |

## 附 B. 已确认**不存在**的方法（不要照名字猜）

- `ClaimedResidence.getBlackList()` / `setBlackList()` —— 不存在；用 `getItemBlacklist()` / `getItemIgnoreList()`。
- `ClaimedResidence.setMainArea(...)` —— 不存在；只有 `getMainArea()` / `getMainAreaName()`(6.0.3.3) / `setMainResidence(boolean)`。
- `ClaimedResidence.getAreas()` —— 不存在；用 `getAreaArray()` / `getAreaMap()` / `getAreaList()`。
- `CuboidArea.getName()` / `setName()` —— 不存在；区域名是 `Map<String, CuboidArea>` 的 key。
- `ClaimedResidence.setOwner(...)` —— 不存在；在 `getPermissions()` 上。
- `ClaimedResidence.expand(...)` / `contract(...)` —— 不存在；走选区 + `replaceArea`。
- `LeaseManager.getLeaseDays()` / `setLeaseDays()` —— 不存在；用 `getExpireTime()` / `setExpireTime(..., int days)`。
- `containers.ELMessageType` —— 6.0.3.3 已删除（6.0.0.1 有），不要 import。
- `Residence` 上的 `getLeaseManager()` 等方法都存在；但 `Residence.getMessageType` 之类不存在，消息类型在 `ConfigManager.getGeneralMessageType()` / `getMessageType()`。

## 附 C. 实测所用命令（可复现）

```powershell
$jdk = "C:\Program Files\Java\jdk-25.0.3\bin\javap.exe"
$cp  = "D:\xiaoyu\领地mod\ResUI-Bridge\libs\Residence6.0.0.1.jar"
$srv = "D:\1.21.11\plugins\[领地]Residence6.0.3.3.jar"

& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.protection.ClaimedResidence
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.protection.CuboidArea
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.protection.FlagPermissions
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.protection.ResidenceManager
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.protection.LeaseManager
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.selection.SelectionManager
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.economy.ResidenceBank
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.economy.TransactionManager
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.economy.rent.RentManager
& $jdk -classpath $cp  -p com.bekvon.bukkit.residence.chat.ChatManager
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.subzone     # 子领地调用路径
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.lset       # lset
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.command    # 命令名单 toggle
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.market     # 市场分支
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.expand     # 扩大
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.message    # 进出消息
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.rc         # 领地聊天
& $jdk -classpath $cp  -p -c com.bekvon.bukkit.residence.commands.setowner   # 转让
# 版本差异：分别对两个 jar 跑 javap -p 后 Compare-Object
```
