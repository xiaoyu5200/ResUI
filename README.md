# ResUI — Residence Management UI

**English** | [简体中文](README_zh-CN.md)

A clean in-game GUI for managing your **Residence** claims, built as a **Fabric client mod** with a lightweight **server-side bridge plugin**.

No more typing `/res` commands to change a flag or add a friend — open the panel, click, done.

- **Client:** Fabric mod for Minecraft **1.21.11**
- **Server:** Paper / Folia plugin (Folia-supported)
- **Requires:** [Residence](https://www.spigotmc.org/resources/residence.11480/) on the server
- **Compatibility:** does **not** replace or intercept the official `/res` commands — everything still works exactly as before

---

## Features

### Claim list
- Two tabs: **My Claims** / **Public Claims**
- Buttons on every row: `Manage` `Details` `Teleport` (public claims only get `Details` / `Teleport`)
- Mouse wheel **and draggable scrollbar**

### Claim details
Owner, world, coordinate bounds, **claim size** (m³ / m²), **claim worth** (buy + sell price), area count and whether teleporting is allowed.

### Management panel (your own claims)
Left-side navigation with three sections:

**1. Claim Info**
- Full details
- `Set Public` / `Set Private` — controls the `tp` flag (whether others may teleport to your claim)
- `Set Teleport Point` — sets your current position as the claim's teleport target

**2. Permissions**
- Lists **every flag the server allows players to change**, each row showing a human-readable name plus the raw flag name
- Built-in **search box** — matches either the translated name or the English flag name
- One click to toggle

**3. Members** (players individually trusted on the claim)
- `Add Member` opens an **online player picker** with live search
- Member list shows each player with their individually-set flag count, plus `Perms` / `Remove`
- Per-player flags are **tri-state**: `Default` (inherits claim settings) → `Allow` → `Deny`

### Feedback & polish
- Every action shows a short toast at the bottom of the screen — green for success, red for failure, with the reason
- Actions are executed **silently**: nothing is spammed into the chat log
- Custom background image (kept at the correct aspect ratio, never stretched)

---

## Requirements

| | |
|---|---|
| Minecraft | **1.21.11** |
| Client | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| Server | Paper or Folia, with **Residence** installed |

---

## Installation

1. **Client** — drop `resui-fabric-1.21.11-<version>.jar` into your instance's `mods` folder.
2. **Server** — drop `ResUI-Bridge-<version>.jar` into the server's `plugins` folder and restart.

> Both sides are required. The client mod alone cannot read claim data.

---

## Usage

- Press **`R`** (rebindable in the vanilla Controls menu), or run **`/resui`**

| Command | Description |
|---|---|
| `/resui` | Refresh data and open the claim list |
| `/resui refresh` | Refresh data only |
| `/resbridge` *(server command)* | Ask the server to re-send your claim data |

> The client command is `/resui` — **not** `/res` — on purpose, so it never shadows the server's Residence command tree.

---

## How the permission list works

The list is **not** hard-coded in the mod; it follows your server configuration:

1. The plugin reads `plugins/Residence/flags.yml` → `Global.FlagPermission`
   - `true` → the flag is enabled on your server and shown in the panel
   - `false` → hidden (these are conventionally server-controlled flags such as `pvp`, `tnt`, `fly`, `backup`, `command`)
2. Player-only flags (`FlagMode = Player`, e.g. `admin`) are excluded from the claim-level list
3. Descriptions come from Residence's own language files (`Language/*.yml`), so they always match your installed Residence version

**Result:** what your players see in the panel is exactly what they are allowed to change — no ghost options that fail when clicked.

---

## Notes & limitations

- **Singleplayer is not supported.** The mod disables itself in singleplayer (including LAN-hosted worlds) and shows a short message, since there is no bridge plugin to talk to.
- You can only manage claims you own (or, for server admins, any claim).
- Teleport uses the vanilla `/res tp` behaviour, so your server's teleport delay/settings still apply.

---

## Building from source

Requires **JDK 21** to compile (use JDK 25 to run Gradle).

```bash
# Client mod
./gradlew -p ResUI-1.21.11 build
# → ResUI-1.21.11/build/libs/resui-fabric-1.21.11-<version>.jar

# Server plugin
./gradlew -p ResUI-Bridge build
# → ResUI-Bridge/build/libs/ResUI-Bridge-<version>.jar
```

---

## License

GPL-3.0
