package aeternum;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent progress, stored in the user's home directory. */
public final class Save {
    private static final Path FILE =
            Path.of(System.getProperty("user.home"), ".imperium-aeternum.properties");

    private Save() {}

    public static void store(PlayerCtrl p, Boss boss) {
        Properties props = new Properties();
        props.setProperty("vigor", Integer.toString(p.vigor));
        props.setProperty("endurance", Integer.toString(p.endurance));
        props.setProperty("strength", Integer.toString(p.strength));
        props.setProperty("gloria", Integer.toString(p.gloria));
        props.setProperty("bossDefeated", Boolean.toString(boss.defeated));
        try (OutputStream out = Files.newOutputStream(FILE)) {
            props.store(out, "IMPERIVM AETERNVM");
        } catch (IOException ignored) {
        }
    }

    /** Applies the saved state, if any. Call after player and boss exist. */
    public static void load(PlayerCtrl p, Boss boss) {
        if (!Files.exists(FILE)) return;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        p.vigor = Integer.parseInt(props.getProperty("vigor", "8"));
        p.endurance = Integer.parseInt(props.getProperty("endurance", "8"));
        p.strength = Integer.parseInt(props.getProperty("strength", "8"));
        p.gloria = Integer.parseInt(props.getProperty("gloria", "0"));
        p.recompute();
        p.hp = p.maxHp;
        p.stamina = p.maxStamina;
        boss.defeated = Boolean.parseBoolean(props.getProperty("bossDefeated", "false"));
    }

    public static void wipe() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException ignored) {
        }
    }
}
