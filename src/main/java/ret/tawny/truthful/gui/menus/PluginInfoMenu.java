package ret.tawny.truthful.gui.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.gui.GuiConstants;
import ret.tawny.truthful.gui.GuiHolder;
import ret.tawny.truthful.gui.GuiItemFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PluginInfoMenu {

    private static final UUID OWNER_UUID = UUID.fromString("e8c0b212-974f-4df5-87e8-d418e2cf84b9");

    public static String getTitle() {
        return GuiConstants.PRIMARY + Truthful.getInstance().getConfiguration().getPluginDisplayName()
                + " " + GuiConstants.DARK + "> " + GuiConstants.MUTED + "System Architecture & Credits";
    }

    public static void open(Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.MenuType.PLUGIN_INFO, null, null, "Credits", 0);
        Inventory inv = Bukkit.createInventory(holder, 54, getTitle());
        GuiItemFactory.fillGradientBorder(inv);

        // ====================================================================
        // APEX (Row 1, Slot 13) - Founder & Principal Architect
        // ====================================================================
        List<String> apexLore = new ArrayList<>();
        apexLore.add(GuiConstants.DARK + "Lead Architect & Founder");
        apexLore.add("");
        apexLore.add(GuiConstants.metric("Role", "Owner & Principal Developer"));
        apexLore.add(GuiConstants.metric("Focus", "Physics Engine & Mathematics"));
        apexLore.add(GuiConstants.metric("Contribution", "Lead Creator & Core Maintainer"));
        apexLore.add("");
        apexLore.add(GuiConstants.PRIMARY + GuiConstants.BOLD + "TOP APEX CONTRIBUTOR");

        ItemStack apexHead = GuiItemFactory.createSkull(
                OWNER_UUID,
                GuiConstants.PRIMARY + GuiConstants.BOLD + "CodeControl / Tawny",
                apexLore.toArray(new String[0])
        );
        GuiItemFactory.addGlow(apexHead);
        inv.setItem(13, apexHead);

        // ====================================================================
        // TIER 2 (Row 2, Slots 21, 22, 23) - Core Architectural Pillars
        // ====================================================================
        inv.setItem(21, GuiItemFactory.createGlowing(
                GuiConstants.getMat("REPEATER", "DIODE"),
                GuiConstants.ACCENT + GuiConstants.BOLD + "PacketEvents Protocol Engine",
                List.of(
                        GuiConstants.DARK + "Network abstraction layer",
                        "",
                        GuiConstants.metric("Type", "Low-level Packet Abstraction"),
                        GuiConstants.metric("Coverage", "Modern 1.20 - 1.21+ Protocols"),
                        GuiConstants.metric("Feature", "Zero-reflection packet pipeline")
                )
        ));

        inv.setItem(22, GuiItemFactory.createGlowing(
                GuiConstants.getMat("NETHER_STAR"),
                GuiConstants.SECONDARY + GuiConstants.BOLD + "Multi-Input Physics Engine",
                List.of(
                        GuiConstants.DARK + "Brute-force kinematic simulation",
                        "",
                        GuiConstants.metric("Type", "Permutation Kinematics"),
                        GuiConstants.metric("Vectoring", "2D/3D candidate analysis"),
                        GuiConstants.metric("Friction", "Per-block dynamic slipperiness")
                )
        ));

        inv.setItem(23, GuiItemFactory.createGlowing(
                GuiConstants.getMat("CLOCK"),
                GuiConstants.SUCCESS + GuiConstants.BOLD + "Folia & Async Concurrency",
                List.of(
                        GuiConstants.DARK + "Region-threaded scheduler",
                        "",
                        GuiConstants.metric("Library", "FoliaLib Thread Abstraction"),
                        GuiConstants.metric("Threading", "Multi-Threaded Safe"),
                        GuiConstants.metric("Offloading", "Netty Thread Pool")
                )
        ));

        // ====================================================================
        // TIER 3 (Row 3, Slots 29, 30, 31, 32, 33) - System Sub-Specifications
        // ====================================================================
        inv.setItem(29, GuiItemFactory.create(
                GuiConstants.getMat("WRITABLE_BOOK", "BOOK_AND_QUILL"),
                GuiConstants.WARNING + GuiConstants.BOLD + "AI Telemetry Recorder",
                List.of(
                        GuiConstants.DARK + "Frame-by-frame JSONL exporter",
                        "",
                        GuiConstants.metric("Format", "JSONL Stream"),
                        GuiConstants.metric("Data", "Ticks, vectors, inputs")
                )
        ));

        inv.setItem(30, GuiItemFactory.create(
                GuiConstants.getMat("BEACON"),
                GuiConstants.LIGHT_PURPLE + GuiConstants.BOLD + "Transaction Sync Engine",
                List.of(
                        GuiConstants.DARK + "Sub-tick ping & velocity confirmation",
                        "",
                        GuiConstants.metric("Type", "Ping / WindowConfirmation Pairs"),
                        GuiConstants.metric("Accuracy", "Sub-tick RTT measurement")
                )
        ));

        inv.setItem(31, GuiItemFactory.create(
                GuiConstants.getMat("ENDER_EYE", "EYE_OF_ENDER"),
                GuiConstants.AQUA + GuiConstants.BOLD + "Rotation Math",
                List.of(
                        GuiConstants.DARK + "GCD grid validation",
                        "",
                        GuiConstants.metric("Math", "Sensitivity step analysis"),
                        GuiConstants.metric("Grid", "Vanilla mouse quantization")
                )
        ));

        inv.setItem(32, GuiItemFactory.create(
                GuiConstants.getMat("GRASS_BLOCK", "GRASS"),
                GuiConstants.SUCCESS + GuiConstants.BOLD + "Async World Cache",
                List.of(
                        GuiConstants.DARK + "Global chunk & bounding box snapshot",
                        "",
                        GuiConstants.metric("Storage", "ChunkSnapshot Global IDs"),
                        GuiConstants.metric("Eviction", "LRU World Eviction Map")
                )
        ));

        inv.setItem(33, GuiItemFactory.create(
                GuiConstants.getMat("CHEST"),
                GuiConstants.HIGHLIGHT + GuiConstants.BOLD + "SQLite WAL Database",
                List.of(
                        GuiConstants.DARK + "Batch-flushed log persistence",
                        "",
                        GuiConstants.metric("Mode", "Write-Ahead Logging (WAL)"),
                        GuiConstants.metric("Pruning", "Automated 14-day retention")
                )
        ));

        // ====================================================================
        // CONTRIBUTORS (Slot 40) - The credit book. Names come from
        // credits.contributors in config.yml, so no code change needed.
        // ====================================================================
        List<String> contribLore = new ArrayList<>();
        contribLore.add(GuiConstants.DARK + "The humans behind the math");
        contribLore.add("");
        contribLore.add(GuiConstants.metric("Owner", GuiConstants.PRIMARY + "CodeControl / Tawny"));
        for (String name : Truthful.getInstance().getConfiguration().getContributors()) {
            contribLore.add(GuiConstants.DARK + GuiConstants.BULLET + " " + GuiConstants.HIGHLIGHT + name);
        }
        contribLore.add("");
        contribLore.add(GuiConstants.MUTED + "30+ real contributions earns a spot here.");
        inv.setItem(40, GuiItemFactory.create(
                GuiConstants.getMat("NAME_TAG"),
                GuiConstants.SECONDARY + GuiConstants.BOLD + "Contributors",
                contribLore
        ));

        // ====================================================================
        // CORNERS & EXTERNAL ACTIONS (Outside Pyramid Structure)
        // ====================================================================
        // Bottom-Left (Slot 45) - Navigation
        inv.setItem(45, GuiItemFactory.createBackButton("Dashboard"));

        // Bottom-Center (Slot 49) - Plugin Overview & Specs
        String version = Truthful.getInstance().getPlugin().getDescription().getVersion();
        inv.setItem(49, GuiItemFactory.createGlowing(
                GuiConstants.getMat("BOOK"),
                GuiConstants.PRIMARY + GuiConstants.BOLD + "TruthfulAC Core",
                List.of(
                        GuiConstants.DARK + "Enterprise Anti-Cheat Solution",
                        "",
                        GuiConstants.metric("Version", "v" + version),
                        GuiConstants.metric("Java", "Java 21 Target"),
                        GuiConstants.metric("API", "Paper / Spigot 1.20 - 1.21+")
                )
        ));

        // Bottom-Right (Slot 53) - Discord Community Link
        inv.setItem(53, GuiItemFactory.createGlowing(
                GuiConstants.getMat("KNOWLEDGE_BOOK", "BOOK"),
                GuiConstants.SUCCESS + GuiConstants.BOLD + "Discord Community",
                List.of(
                        GuiConstants.DARK + "Official support & releases",
                        "",
                        GuiConstants.SUCCESS + GuiConstants.SYM_ARROW + " Click to receive link"
                )
        ));

        player.openInventory(inv);
    }

    public static void sendDiscordLink(Player player) {
        player.closeInventory();
        player.sendMessage(GuiConstants.separator());
        player.sendMessage(GuiConstants.ACCENT + GuiConstants.BOLD + "Truthful Support & Releases");
        player.sendMessage(GuiConstants.MUTED + "https://discord.gg/AnQvddTZDg");
        player.sendMessage(GuiConstants.separator());
    }

    private PluginInfoMenu() {}
}