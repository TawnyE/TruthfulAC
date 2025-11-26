<div align="center">

# 🛡️ Truthful Anti-Cheat
### The legacy 1.8 solution, modernized for the future.

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![Platforms](https://img.shields.io/badge/Supported-1.8%20--%201.21+-lightgrey)
![License](https://img.shields.io/badge/License-Custom-blue)
[![Discord](https://img.shields.io/discord/123456789?color=5865F2&label=Discord)](https://discord.gg/xRchyJFkBG)

</div>

---

## ⚠️ **Beta Disclaimer**
> **Current Status:** Public Beta / Development Build  
> TruthfulAC is currently being **ported and rewritten** from its original 2019 codebase. While it supports versions **1.8 through 1.21**, it is not yet recommended for large-scale production servers without prior testing. Expect edge-case bugs as we finalize the new check architecture.

---

## 📖 **About**
Originally established in **2019**, TruthfulAC has been revived to fill a specific gap in the market: **A lightweight, packet-based anti-cheat that maintains strict 1.8 PvP mechanics while supporting modern server infrastructure.**

We prioritize clean code, low performance overhead, and "set-and-forget" configuration.

---

## ✨ **Key Features**

### 🛡️ **Detection System**
- **Combat:**
  - **Reach:** Historical raytracing to detect extended reach (3.0+ blocks).
  - **Hitbox:** Prevents hitting entities outside the field of view or through walls.
  - **Aim:** Pattern analysis to detect KillAura and irregular mouse movements.
- **Movement:**
  - **Speed:** Multi-layered friction and momentum analysis.
  - **Fly:** Kinetic energy and gravity prediction.
  - **Elytra:** Prevents infinite flight and unrealistic acceleration.
  - **Vehicle:** Checks for BoatFly and entity speed exploits.
- **World:**
  - **Scaffold:** Analyzes placement rotations, timing, and vectors.
  - **BadPackets:** Prevents OmniSprint, crashers, and invalid interactions.

### 🚀 **Core Features**
- **Multi-Version Support:** Runs seamlessly on 1.8.8 up to 1.21+.
- **Bedrock Compatible:** Native support for **Geyser/Floodgate** players (auto-exemption logic).
- **Database Logging:** Violations are saved to a local SQLite database for long-term history.
- **In-Game GUI:** Full inventory menu to manage checks and view player logs.
- **Lag Compensation:** Transaction-based tracking to prevent false positives during lag spikes.

---

## 📥 **Installation**

1. **Download** the latest JAR file.
2. Ensure you have **[ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)** installed (Required).
3. *(Optional)* Install **Floodgate** if you want Bedrock player support.
4. Drag `Truthful.jar` into your `/plugins/` folder.
5. **Restart** your server.

---

## 💻 **Commands & Permissions**

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/truthful menu` | `truthful.admin` | Opens the main GUI. |
| `/truthful logs <player>` | `truthful.admin` | View historical logs for a player. |
| `/truthful info <player>` | `truthful.admin` | View client brand and ping. |
| `/truthful exempt <player>` | `truthful.admin` | Toggle check exemptions for a specific user. |
| `/truthful export` | `truthful.admin` | Export all logs to a CSV file. |

**Alert Node:** `truthful.alerts` (Grants visibility of anti-cheat notifications).

---

## 🧩 **Community Guidelines**

1. **No Support Begging:** This is a free, open-source project. Updates are released on our schedule.
2. **Contribution Policy:** Private forks and rebranding are strictly prohibited. All modifications must remain open-source.
3. **Bug Reporting:** Do not complain about false positives—**report them**. Open a ticket with logs and reproduction steps.

---

## 💬 **Support**

Need help or want to report a bypass? Join our community:
👉 [**Join the Discord Server**](https://discord.gg/xRchyJFkBG)
