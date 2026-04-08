package com.dtech.kitecon.watchlist;

import lombok.Data;
import java.util.List;

@Data
public class WatchlistDto {
    private Long id;
    private String name;
    private List<String> symbols;

    public static WatchlistDto from(Watchlist w) {
        WatchlistDto dto = new WatchlistDto();
        dto.setId(w.getId());
        dto.setName(w.getName());
        dto.setSymbols(w.getSymbols().stream().map(WatchlistSymbol::getSymbol).toList());
        return dto;
    }
}
