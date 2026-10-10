# ResUI —— Residence 领地管理 UI（Fabric 客户端 mod + 服务端桥接插件）

[English](README.md) | **简体中文**

给装了 [Residence](https://www.spigotmc.org/resources/residence.11480/) 的 Paper / Folia 服务器用的领地管理界面。
客户端是 Fabric mod，服务端是一个轻量桥接插件，两者通过自定义插件通道通信。

- 客户端 mod：`ResUI-1.21.11/`（modid `resui`，当前 **0.4.19**）
- 服务端插件：`ResUI-Bridge/`（当前 **0.1.18**）
- 版本要求：**Minecraft 1.21.11**，客户端 Fabric + Fabric API；服务端 Paper / Folia（插件已标记 `folia-supported`）

---

## 一、安装

| 端 | 文件 | 放到 |
|---|---|---|
| 客户端 | `resui-fabric-1.21.11-<版本>.jar` | 整合包 / 客户端的 `mods` 目录 |
| 服务端 | `ResUI-Bridge-<版本>.jar` | 服务器的 `plugins` 目录 |

- 服务端需要先装 **Residence**；不装也不会报错，界面里会提示「Residence 未安装」。
- 两个 jar 要**配套更新**：协议字段是同步演进的，只换一边可能显示不全。

---

## 二、打开界面

- **快捷键 `R`**（可在原版按键设置里改）
- 或输入客户端命令 **`/resui`**

> 注意：客户端命令刻意叫 `/resui` 而不是 `/res`——否则会拦截服务端 Residence 的命令树，导致 `/res tp` 之类的原生命令失效。

> **单人模式（含本地开房间）下不可用**：本 mod 依赖服务端桥接插件，单人世界里没有它，所以按 `R` 或输入 `/resui` 会直接被拒绝，聊天栏提示「单人模式下不可用（需要服务端安装 ResUI-Bridge 插件）」。

命令列表：

| 命令 | 作用 |
|---|---|
| `/resui` | 刷新数据并打开领地列表 |
| `/resui refresh` | 只刷新数据 |
| `/resbridge`（服务端命令） | 让服务器把领地数据重新推给你 |

---

## 三、功能

### 领地列表

- 两个页签：**我的领地** / **公共领地**（右上角可刷新）
- 每行的按钮：
  - 我的领地：`管理` `详细` `传送`
  - 公共领地：`详细` `传送`
- 列表**不显示坐标**，坐标等信息收在「详细」弹窗里
- 支持滚轮 + **拖动右侧滚动条**

### 详细弹窗

显示：所有者、世界、坐标范围、**领地大小**（m³ / m²）、**领地价值**（购买价 + 出售价）、区域数、是否可传送。

### 管理面板（仅对自己的领地开放）

点行内 `管理` 进入，左侧有三个导航项：

**1. 领地信息**
- 上述详情
- `设为公开` / `设为私有`：控制 tp 权限（别人能否 `/res tp` 到你的领地）
- `设置传送点`：把**你当前站的位置**设为该领地传送点（需站在自己的领地范围内）

**2. 权限管理**
- 列出该领地上**允许玩家修改的全部权限**，每行「中文说明 + 英文 flag 名」，右侧开关
- 顶部搜索框：中英文都能匹配（输「建造」或 `build` 都行）

**3. 领地成员**（= 被单独授权过的玩家）
- `添加成员` → 进入**在线玩家选择页**：实时在线玩家列表 + 搜索框，点「添加」即可
- 成员列表：玩家名 + 已单独设置的权限数，右侧 `权限` / `移除`
- 点 `权限` → 该成员的权限页，三态开关：
  `默认`（灰，继承领地权限）→ `允许`（绿）→ `禁止`（红）→ 回到默认

### 操作反馈

所有写操作（改权限、加/删成员、设传送点）执行后，界面底部会弹一条提示，成功绿色 / 失败红色，约 4 秒消失，例如：

```
[成功] 已设置 tp = false
[失败] 设置未生效，请点右上角刷新查看
```

同时**不会再往聊天栏刷 Residence 的命令回显**（插件是直接调 API 执行的）。

### 界面外观

- **背景图**：`ResUI-1.21.11/src/main/resources/assets/resui/textures/gui/background.png`（PNG，1280×720）
  - 绘制方式为「保持宽高比铺满」：窗口比例不同时自动等比缩放，多余部分居中裁掉，**不会把人/图拉变形**
  - 全屏另压一层 13% 黑遮罩，保证面板外的文字也能看清
  - **换图**：直接替换这个 PNG（必须是 PNG，路径里的文件名不能改），重新构建即可；图片建议 1920×1080 以上，太小的图放大后会糊
- **mod 图标**：`assets/resui/icon.png`（256×256），由 `fabric.mod.json` 的 `icon` 字段引用，在 PCL / Mod Menu 的 mod 列表里显示
- 两个界面（领地列表、管理面板）共用同一张背景图

---

## 四、权限清单是怎么来的（重要）

界面里显示哪些权限，**完全按服务器配置**，不是 mod 自己写死的：

1. 读 `plugins/Residence/flags.yml` 的 **`Global.FlagPermission`** 节点
   - 值为 `true` → 该权限在服务器上启用，界面显示
   - 值为 `false`（默认有 `pvp`、`tnt`、`fly`、`backup`、`command`、`damage` 等）→ 不显示，玩家也改不了
2. 再排除**仅玩家级**的权限（`FlagMode = Player`，如 `admin`），它们不属于领地设置项
3. 中文说明读 `plugins/Residence/Language/Chinese.yml`；官方尚未翻译的新权限由插件内置补充（按官方英文描述翻译）

所以：

- 想让玩家能自己开关 `pvp` → 把 `flags.yml` 里 `FlagPermission.pvp` 改成 `true`，重启或 `/res reload`，界面会自动多出这一项
- 玩家缺少某个 `residence.flag.<名字>` 权限节点时，该权限本来就不该改（Residence 官方逻辑），界面也不会给他看

---

## 五、构建

需要 **JDK 21** 编译（Gradle 本体需 JDK 25 运行 Loom 1.17.x 插件链）。

服务端插件编译前，先把 Residence 的 jar 放进 `ResUI-Bridge/libs/`（见该目录下的 README.md）。

```bash
# 客户端 mod
./gradlew -p ResUI-1.21.11 build
# 产物：ResUI-1.21.11/build/libs/resui-fabric-1.21.11-<版本>.jar

# 服务端插件
./gradlew -p ResUI-Bridge build
# 产物：ResUI-Bridge/build/libs/ResUI-Bridge-<版本>.jar
```

Windows 下把 `./gradlew` 换成 `gradlew.bat` 即可。

---

## 六、技术要点（改代码时看）

**通信协议**（VarInt 长度 + UTF-8 JSON，与 `FriendlyByteBuf.readUtf` 对齐）：

| 通道 | 方向 | 用途 |
|---|---|---|
| `resui:main` | S2C | 推送领地快照 + 操作结果 |
| `resui:refresh` | C2S | 请求刷新 |
| `resui:action` | C2S | 写操作（改权限 / 成员 / 传送点） |

- 服务端所有操作都在**玩家所属区域线程**执行（`player.getScheduler().run`），兼容 Folia
- 写操作在服务端做**二次校验**：确认调用者是领地主人（或 `residence.admin`）、权限名在允许清单内、玩家名合法——不信任客户端传来的参数
- 客户端界面全部**自绘矩形**，不使用原版控件；`EditBox` 只在早期版本用过，现已换成自绘搜索框（原版控件尺寸不受控）

**1.21.11 API 注意点**：
- `ResourceLocation` → `Identifier`
- 输入事件为 record：`mouseClicked(MouseButtonEvent, boolean)`、`mouseDragged(MouseButtonEvent, double, double)`、`mouseReleased(MouseButtonEvent)`、`keyPressed(KeyEvent)`、`charTyped(CharacterEvent)`
- `CustomPacketPayload.Type` 必须用 `new Type<>(Identifier.fromNamespaceAndPath("resui","xxx"))`
- `GameProfile` 是 record，名字用 `name()` 而不是 `getName()`

---

## 七、常见问题

**Q：界面上什么都没有 / 提示服务端未装插件**
客户端和服务端 jar 是否都是最新、配套的。

**Q：点了权限没反应**
先看界面底部提示条。若显示「设置未生效」，多半是 Residence 那边没接受这次修改（配置或权限问题）。

**Q：列表里的权限和官方 `/res set` 界面数量对不上**
以 `flags.yml` 的 `Global.FlagPermission` 为准（见第四节）。官方 GUI 和本 mod 用的是同一份判定。

**Q：领地数量上限怎么调**
那是 Residence 的**权限组**配置，在 `plugins/Residence/groups.yml`：
- 改组内 `MaxResidences` 会影响整个组
- 只给某个人加：新建一个组（照抄 `Default`，只改 `MaxResidences`），再用
  `GroupAssignments: 玩家名: 组名`，或给他权限节点 `residence.group.<组名小写>`
- 注意别直接把人换到 `NextGroup` 那种尺寸很小的组，否则新建的领地会变得很小
