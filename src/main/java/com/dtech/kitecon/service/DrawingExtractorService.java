package com.dtech.kitecon.service;

import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * DrawingExtractorService - Extracts drawings from TradingView chart state JSON
 * Parses chart state and converts to normalized Drawing objects
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DrawingExtractorService {

    private final ObjectMapper objectMapper;

    /**
     * Extract all drawings from chart state JSON
     *
     * @param chartStateJson Raw chart state from TradingView widget
     * @return List of normalized Drawing objects
     */
    public List<ValidationInput.Drawing> extractDrawings(String chartStateJson) {
        List<ValidationInput.Drawing> drawings = new ArrayList<>();

        try {
            JsonNode chartState = objectMapper.readTree(chartStateJson);

            if (chartState.isObject()) {
                List<String> keys = new ArrayList<>();
                chartState.fieldNames().forEachRemaining(keys::add);
                log.info("Chart state keys: {}", String.join(", ", keys));
            } else {
                log.info("Chart state is not an object");
            }

            // TradingView stores drawings in multiple possible locations:
            // 1. charts[0].panes[0].sources (from chart.save())
            // 2. state.sources (line tools, shapes)
            // 3. state.drawings (alternative location)
            // 4. lineTools (from getLineToolsState())

            // Try chart.save() format: charts[0].panes[0].sources
            if (chartState.has("charts") && chartState.get("charts").isArray()
                && chartState.get("charts").size() > 0) {
                JsonNode chart = chartState.get("charts").get(0);
                if (chart.has("panes") && chart.get("panes").isArray()) {
                    for (JsonNode pane : chart.get("panes")) {
                        if (pane.has("sources")) {
                            drawings.addAll(extractFromSources(pane.get("sources")));
                        }
                    }
                }
            }

            // Try direct sources
            if (chartState.has("sources")) {
                drawings.addAll(extractFromSources(chartState.get("sources")));
            }

            // Try drawings array
            if (chartState.has("drawings")) {
                drawings.addAll(extractFromDrawings(chartState.get("drawings")));
            }

            // Try lineTools
            if (chartState.has("lineTools")) {
                drawings.addAll(extractFromLineTools(chartState.get("lineTools")));
            }

            log.info("Extracted {} drawings from chart state", drawings.size());

        } catch (Exception e) {
            log.error("Error extracting drawings from chart state", e);
        }

        return drawings;
    }

    /**
     * Extract drawings from 'sources' node
     * This is the primary location for TradingView drawings
     */
    private List<ValidationInput.Drawing> extractFromSources(JsonNode sources) {
        List<ValidationInput.Drawing> drawings = new ArrayList<>();

        if (sources == null || !sources.isObject()) {
            return drawings;
        }

        sources.fields().forEachRemaining(entry -> {
            String sourceId = entry.getKey();
            JsonNode source = entry.getValue();

            try {
                ValidationInput.Drawing drawing = parseSource(source);
                if (drawing != null) {
                    drawings.add(drawing);
                }
            } catch (Exception e) {
                log.warn("Error parsing source {}: {}", sourceId, e.getMessage());
            }
        });

        return drawings;
    }

    /**
     * Parse a single source into a Drawing.
     * A drawing is included even if its points cannot be fully parsed — the AI
     * still benefits from knowing the drawing type and label.
     */
    private ValidationInput.Drawing parseSource(JsonNode source) {
        // Accept both "type" and "toolname" — getChartState() uses "toolname || type"
        String rawType = null;
        if (source.has("type") && !source.get("type").isNull()) {
            rawType = source.get("type").asText();
        } else if (source.has("toolname") && !source.get("toolname").isNull()) {
            rawType = source.get("toolname").asText();
        }
        if (rawType == null || rawType.isBlank()) {
            return null;
        }

        String normalizedType = normalizeDrawingType(rawType);

        // Try extracting points from the node itself, then fall back to the
        // nested "state" object that TradingView's getLineToolsState() uses.
        List<ValidationInput.Point> points = extractPoints(source);
        if (points.isEmpty() && source.has("state")) {
            points = extractPoints(source.get("state"));
        }

        // Don't require points — a type-only drawing still gives the AI context.
        ValidationInput.DrawingProperties properties = extractProperties(source);

        return ValidationInput.Drawing.builder()
            .type(normalizedType)
            .points(points)
            .properties(properties)
            .build();
    }

    /**
     * Normalize TradingView drawing type names
     */
    private String normalizeDrawingType(String tvType) {
        return switch (tvType.toLowerCase()) {
            case "linetooltrendline", "linetool_trend_line", "trendline" -> "trendline";
            case "linetoolhorzline", "linetool_horz_line", "horizontalline" -> "horizontal_line";
            case "linetooltriangle", "linetool_triangle" -> "triangle";
            case "linetoolfibonacci", "linetool_fib_retracement" -> "fibonacci";
            case "linetoolchannel", "linetool_parallel_channel" -> "channel";
            case "linetoolrectangle", "linetool_rectangle" -> "rectangle";
            case "linetoolelliottimpulse" -> "elliott_impulse";
            case "linetoolelliottcorrection" -> "elliott_corrective";
            case "linetoolpitchfork" -> "pitchfork";
            case "linetoolganfan" -> "gann_fan";
            default -> tvType.toLowerCase().replace("linetool", "").replace("_", "");
        };
    }

    /**
     * Extract points from a drawing source
     * Points can be stored in various formats depending on drawing type
     */
    private List<ValidationInput.Point> extractPoints(JsonNode source) {
        List<ValidationInput.Point> points = new ArrayList<>();

        // Check for 'points' array
        if (source.has("points") && source.get("points").isArray()) {
            for (JsonNode pointNode : source.get("points")) {
                ValidationInput.Point point = parsePoint(pointNode);
                if (point != null) {
                    points.add(point);
                }
            }
        }

        // Check for individual point fields (point1, point2, etc.)
        for (int i = 1; i <= 10; i++) {
            String fieldName = "point" + i;
            if (source.has(fieldName)) {
                ValidationInput.Point point = parsePoint(source.get(fieldName));
                if (point != null) {
                    points.add(point);
                }
            }
        }

        // Check for coordinate pairs
        if (source.has("coordinates") && source.get("coordinates").isArray()) {
            for (JsonNode coord : source.get("coordinates")) {
                if (coord.has("time") && coord.has("price")) {
                    points.add(ValidationInput.Point.builder()
                        .timestamp(coord.get("time").asLong())
                        .price(coord.get("price").asDouble())
                        .build());
                }
            }
        }

        return points;
    }

    /**
     * Parse a single point. TradingView uses several different field names:
     *  - { time_t, price }       ← getLineToolsState() format (actual data)
     *  - { time,   price }       ← chart.save() format
     *  - { timestamp, price }
     *  - { x, y }
     *
     * Rules:
     *  - price == 0 is the only hard discard.
     *  - timestamp == 0 is allowed (horizontal lines have no time anchor).
     *  - ms timestamps (> 1e10) are normalised to seconds.
     */
    private ValidationInput.Point parsePoint(JsonNode pointNode) {
        if (pointNode == null || !pointNode.isObject()) {
            return null;
        }

        long timestamp = 0;
        double price = 0;

        // --- timestamp (time_t is the actual TradingView field name) ---
        if (pointNode.has("time_t")) {
            timestamp = pointNode.get("time_t").asLong();
        } else if (pointNode.has("time")) {
            timestamp = pointNode.get("time").asLong();
        } else if (pointNode.has("timestamp")) {
            timestamp = pointNode.get("timestamp").asLong();
        } else if (pointNode.has("x")) {
            timestamp = pointNode.get("x").asLong();
        }
        if (timestamp > 10_000_000_000L) {
            timestamp = timestamp / 1000;
        }

        // --- price ---
        if (pointNode.has("price")) {
            price = pointNode.get("price").asDouble();
        } else if (pointNode.has("value")) {
            price = pointNode.get("value").asDouble();
        } else if (pointNode.has("y")) {
            price = pointNode.get("y").asDouble();
        }

        if (price == 0) {
            return null;
        }

        return ValidationInput.Point.builder()
            .timestamp(timestamp)
            .price(price)
            .build();
    }

    /**
     * Extract drawing properties.
     * After the getChartState() fix, visual properties arrive inside a nested
     * "properties" node (which maps to inner.state in TradingView's structure).
     * We check both the node itself and its "properties" child so both the
     * chart.save() format and the getLineToolsState() format work.
     */
    private ValidationInput.DrawingProperties extractProperties(JsonNode source) {
        ValidationInput.DrawingProperties.DrawingPropertiesBuilder builder =
            ValidationInput.DrawingProperties.builder();

        // Resolve the node that actually holds visual props.
        // After our TVChartApp fix: { type, points, properties: { linecolor, ... } }
        JsonNode props = source;
        if (source.has("properties") && source.get("properties").isObject()) {
            props = source.get("properties");
        }

        if (props.has("linecolor") || props.has("color")) {
            builder.color(props.has("linecolor") ? props.get("linecolor").asText() : props.get("color").asText());
        }

        if (props.has("linewidth") || props.has("width")) {
            builder.lineWidth(props.has("linewidth") ? props.get("linewidth").asInt() : props.get("width").asInt());
        }

        if (props.has("linestyle") || props.has("style")) {
            builder.lineStyle(normalizeLineStyle(
                props.has("linestyle") ? props.get("linestyle").asText() : props.get("style").asText()));
        }

        // "title" is more reliable than "text" for user-set labels in TradingView
        if (props.has("title") && !props.get("title").asText().isBlank()) {
            builder.label(props.get("title").asText());
        } else if (props.has("text") && !props.get("text").asText().isBlank()) {
            builder.label(props.get("text").asText());
        } else if (props.has("label")) {
            builder.label(props.get("label").asText());
        }

        return builder.build();
    }

    /**
     * Normalize line style values
     */
    private String normalizeLineStyle(String style) {
        return switch (style.toLowerCase()) {
            case "0", "solid" -> "solid";
            case "1", "dashed" -> "dashed";
            case "2", "dotted" -> "dotted";
            default -> "solid";
        };
    }

    /**
     * Extract from 'drawings' node (alternative location)
     * Drawings can be either an array or an object
     */
    private List<ValidationInput.Drawing> extractFromDrawings(JsonNode drawings) {
        List<ValidationInput.Drawing> result = new ArrayList<>();

        if (drawings == null) {
            return result;
        }

        // Handle array format (from frontend)
        if (drawings.isArray()) {
            for (JsonNode drawing : drawings) {
                try {
                    ValidationInput.Drawing parsed = parseSource(drawing);
                    if (parsed != null) {
                        result.add(parsed);
                    }
                } catch (Exception e) {
                    log.warn("Error parsing drawing from array: {}", e.getMessage());
                }
            }
        }
        // Handle object format (keyed by ID)
        else if (drawings.isObject()) {
            return extractFromSources(drawings);
        }

        return result;
    }

    /**
     * Extract from 'lineTools' node (from getLineToolsState())
     */
    private List<ValidationInput.Drawing> extractFromLineTools(JsonNode lineTools) {
        // Similar to extractFromSources, but adapted for lineTools structure
        return extractFromSources(lineTools);
    }

    /**
     * Find specific drawing type from list
     */
    public ValidationInput.Drawing findDrawingByType(
        List<ValidationInput.Drawing> drawings,
        String type
    ) {
        return drawings.stream()
            .filter(d -> type.equalsIgnoreCase(d.getType()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Find all drawings of specific type
     */
    public List<ValidationInput.Drawing> findDrawingsByType(
        List<ValidationInput.Drawing> drawings,
        String type
    ) {
        return drawings.stream()
            .filter(d -> type.equalsIgnoreCase(d.getType()))
            .toList();
    }

    /**
     * Count drawings by type
     */
    public long countDrawingsByType(List<ValidationInput.Drawing> drawings, String type) {
        return drawings.stream()
            .filter(d -> type.equalsIgnoreCase(d.getType()))
            .count();
    }
}
