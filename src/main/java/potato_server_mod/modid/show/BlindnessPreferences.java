package potato_server_mod.modid.show;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Per-world preferences. Players receive bystander blindness unless they opt out. */
public final class BlindnessPreferences {
    private final Path file;
    private Set<UUID> disabled = new HashSet<>();

    public BlindnessPreferences(Path file) {
        this.file = file;
    }

    public void load() throws IOException {
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Set<UUID> loaded = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!"false".equalsIgnoreCase(properties.getProperty(key))) continue;
            try {
                loaded.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // A manually edited, invalid UUID must not discard other players' choices.
            }
        }
        disabled = loaded;
    }

    public boolean isEnabled(UUID player) {
        return !disabled.contains(player);
    }

    public void setEnabled(UUID player, boolean enabled) throws IOException {
        Set<UUID> updated = new HashSet<>(disabled);
        if (enabled) updated.remove(player);
        else updated.add(player);

        Properties properties = new Properties();
        updated.forEach(id -> properties.setProperty(id.toString(), "false"));
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Potato Server: receive blindness from other players (default true)");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            disabled = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
