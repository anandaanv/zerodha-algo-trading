package com.dtech.aitrader.web;

import java.time.LocalDateTime;

public record SymbolStatus(String symbol, LocalDateTime lastRunAt) {}
