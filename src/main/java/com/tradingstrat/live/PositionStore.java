package com.tradingstrat.live;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingstrat.config.LiveExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable record of which symbols are currently held.
 *
 * <p>Hysteresis is stateful: the rule keeps its previous position while price sits inside
 * the band, so a process that restarts without knowing what it holds will act on the
 * wrong side of the dead zone. Balances alone cannot substitute — a non-zero balance does
 * not say whether the strategy bought it or whether it was already there.
 */
@Component
public class PositionStore {

    private static final Logger log = LoggerFactory.getLogger(PositionStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final Map<String, Integer> positions = new LinkedHashMap<>();

    public PositionStore(LiveExecutorProperties props) {
        this.file = Path.of(props.stateDir(), "positions.json");
        load();
    }

    /** 1 when held, 0 when flat. */
    public synchronized int get(String symbol) {
        return positions.getOrDefault(symbol, 0);
    }

    /** Persist immediately: a crash between the order and the write would desynchronise. */
    public synchronized void set(String symbol, int position) {
        positions.put(symbol, position);
        save();
    }

    public synchronized Map<String, Integer> all() {
        return new LinkedHashMap<>(positions);
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                positions.putAll(mapper.readValue(Files.readAllBytes(file),
                        new TypeReference<LinkedHashMap<String, Integer>>() {}));
                log.info("Loaded live positions from {}: {}", file, positions);
            } else {
                log.info("No position file at {}; starting flat", file);
            }
        } catch (IOException ex) {
            // Guessing here would be worse than refusing to start.
            throw new IllegalStateException(
                    "Could not read position state at " + file + "; refusing to trade blind", ex);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(positions));
        } catch (IOException ex) {
            log.error("Could not persist positions to {}: {}", file, ex.getMessage());
        }
    }
}
