package ret.tawny.truthful.database;

import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.PrintWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class LogManager {

    private Connection connection;
    private final Plugin plugin;

    public LogManager(Plugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        try {
            File dataFolder = new File(plugin.getDataFolder(), "database.db");
            if (!dataFolder.getParentFile().exists()) {
                dataFolder.getParentFile().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS violations (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uuid VARCHAR(36), " +
                        "player VARCHAR(16), " +
                        "check_name VARCHAR(64), " +
                        "vl INTEGER, " +
                        "ping INTEGER, " +
                        "data TEXT, " +
                        "timestamp LONG);");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize SQLite database!");
            e.printStackTrace();
        }
    }

    public void log(UUID uuid, String playerName, String checkName, int vl, long ping, String debug) {
        // Standard Insert (Async run handled by caller or here if needed)
        // Note: Since we call this from async check threads often, direct execution is fine
        // if connection is thread-safe (SQLite single connection is usually fine for low volume)
        // But ideally, wrap in scheduler. For brevity in this snippet:
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO violations (uuid, player, check_name, vl, ping, data, timestamp) VALUES(?,?,?,?,?,?,?);")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, checkName);
            ps.setInt(4, vl);
            ps.setLong(5, ping);
            ps.setString(6, debug);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<LogEntry> getLogs(String playerName, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM violations WHERE player = ? ORDER BY timestamp DESC LIMIT ?;")) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                logs.add(new LogEntry(
                        rs.getString("player"),
                        rs.getString("check_name"),
                        rs.getInt("vl"),
                        rs.getString("data"),
                        rs.getLong("timestamp")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return logs;
    }

    // --- NEW EXPORT FEATURE ---
    public File exportToCsv() {
        File exportFolder = new File(plugin.getDataFolder(), "exports");
        if (!exportFolder.exists()) exportFolder.mkdirs();

        String fileName = "logs-" + new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date()) + ".csv";
        File file = new File(exportFolder, fileName);

        try (PrintWriter writer = new PrintWriter(file);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM violations ORDER BY timestamp DESC;")) {

            // CSV Header
            writer.println("ID,UUID,Player,Check,VL,Ping,Data,Timestamp,Date");

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            while (rs.next()) {
                long time = rs.getLong("timestamp");
                String dateStr = sdf.format(new Date(time));

                // Escape commas in data
                String safeData = rs.getString("data").replace(",", ";");

                writer.printf("%d,%s,%s,%s,%d,%d,%s,%d,%s%n",
                        rs.getInt("id"),
                        rs.getString("uuid"),
                        rs.getString("player"),
                        rs.getString("check_name"),
                        rs.getInt("vl"),
                        rs.getLong("ping"),
                        safeData,
                        time,
                        dateStr
                );
            }
            return file;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static class LogEntry {
        public final String player, check, data;
        public final int vl;
        public final long timestamp;

        public LogEntry(String player, String check, int vl, String data, long timestamp) {
            this.player = player;
            this.check = check;
            this.vl = vl;
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}