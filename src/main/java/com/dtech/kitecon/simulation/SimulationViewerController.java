package com.dtech.kitecon.simulation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * REST endpoints for accessing persisted simulation results.
 * Serves JSON files from /tmp/sim-results/ directory.
 */
@RestController
@RequestMapping("/api/simulation-results")
public class SimulationViewerController {

    private static final Path RESULTS_DIR = Paths.get("/tmp/sim-results");

    /**
     * GET /api/simulation-results
     * Returns the index of all available simulation runs.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public String getIndex() throws IOException {
        Path indexFile = RESULTS_DIR.resolve("_index.json");
        if (!Files.exists(indexFile)) {
            return "{\"runs\": []}";
        }
        return Files.readString(indexFile);
    }

    /**
     * GET /api/simulation-results/{runId}
     * Returns the full simulation run data for the specified run ID.
     */
    @GetMapping(value = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getRun(@PathVariable String runId) throws IOException {
        Path runFile = RESULTS_DIR.resolve(runId + ".json");
        if (!Files.exists(runFile)) {
            return "{\"error\": \"Run not found: " + runId + "\"}";
        }
        return Files.readString(runFile);
    }
}
