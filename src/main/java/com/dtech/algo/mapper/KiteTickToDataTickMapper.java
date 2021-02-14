package com.dtech.algo.mapper;

import com.dtech.algo.runner.candle.DataTick;
import com.zerodhatech.models.Tick;
import org.mapstruct.Mapper;

@Mapper
public interface KiteTickToDataTickMapper extends GenericMapper<Tick, DataTick> {
}
