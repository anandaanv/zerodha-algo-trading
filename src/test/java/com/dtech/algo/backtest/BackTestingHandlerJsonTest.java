package com.dtech.algo.backtest;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import com.dtech.algo.strategy.helper.ComponentHelper;
import com.dtech.kitecon.KiteconApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@SpringBootTest(classes = {KiteconApplication.class})
class BackTestingHandlerJsonTest {

    @MockBean
    private BarSeriesLoader barSeriesLoader;

    @Autowired
    private ComponentHelper componentHelper;

    @Autowired
    private BackTestingHandlerJson backTestingHandlerJson;

    private ObjectWriter objectMapper = getObjectMapper().writerWithDefaultPrettyPrinter();

    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Test
    void execute() throws StrategyException, JsonProcessingException {
        String sbin = "sbin";
        BarSeriesConfig barSeriesConfig = getBarSeriesConfigSbinCash15Min(LocalDate.now(), LocalDate.now());
        componentHelper.setupBarSeries(sbin);
        StrategyConfig strategyConfig = componentHelper.buildSimpleSmaStrategy();
        List<BarSeriesConfig> barSeriesConfigs = Collections.singletonList(barSeriesConfig);

        BacktestInput backtestInput = BacktestInput.builder()
                .barSeriesConfigs(barSeriesConfigs)
                .barSeriesName(sbin)
                .strategyConfig(strategyConfig)
                .build();

        System.out.println(objectMapper.writeValueAsString(backtestInput));

        BacktestResult backtestResult = backTestingHandlerJson.execute(backtestInput);
        double totalProfit = backtestResult.getAggregatesResults().get("TotalProfit");
        Assertions.assertEquals(totalProfit, 281.73, 0.001);

    }

    private BarSeriesConfig getBarSeriesConfigSbinCash15Min(LocalDate endDate, LocalDate startDate) {
        return BarSeriesConfig.builder()
                .seriesType(SeriesType.EQUITY)
                .exchange(Exchange.NSE)
                .instrument("SBIN")
                .instrumentType(InstrumentType.EQ)
                .interval(Interval.FifteenMinute)
                .name("sbin15min")
                .endDate(endDate)
                .startDate(startDate)
                .build();
    }
}