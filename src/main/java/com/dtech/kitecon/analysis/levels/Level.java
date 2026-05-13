package com.dtech.kitecon.analysis.levels;

import java.time.Instant;

public record Level(
    double price,
    LevelType type,
    double score,
    Instant createdAt,
    String description
) {}
