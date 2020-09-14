package com.dtech.kitecon.strategy.backtest;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.backtest.TradeRecord.TradeRecordBuilder;
import com.dtech.kitecon.strategy.dataloader.InstrumentDataLoader;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.analysis.criteria.*;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;

import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class BackTestingHandler {

    String[] exchanges= new String[]{"NSE", "NFO"};

    private final InstrumentRepository instrumentRepository;
    private final InstrumentDataLoader instrumentDataLoader;

    public BacktestResult execute(String instrumentName, StrategyBuilder strategyBuilder) {
        Instrument tradingIdentity = instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, exchanges);
        Map<Instrument, BarSeries> BarSeriesMap = instrumentDataLoader.loadData(instrumentName);
        Strategy strategy = strategyBuilder.build(tradingIdentity, BarSeriesMap);

        BarSeriesManager seriesManager = new BarSeriesManager(BarSeriesMap.get(tradingIdentity));
        TradingRecord tradingRecord = seriesManager.run(strategy, Order.OrderType.BUY, PrecisionNum.valueOf(1));
        List<TradeRecord> trades = tradingRecord.getTrades().stream()
            .map(this::mapTradeRecord)
            .collect(Collectors.toList());
        return new BacktestResult(backtest(BarSeriesMap.get(tradingIdentity), tradingRecord), trades);
        
    }

    private TradeRecord mapTradeRecord(Trade trade) {
        return TradeRecord.builder()
            .entry(buildOrder(trade.getEntry()))
            .exit(buildOrder(trade.getExit()))
            .profit(trade.getProfit().doubleValue()).build();
    }

    private OrderRecord buildOrder(Order order) {
        return OrderRecord.builder()
            .amount(order.getAmount().doubleValue())
            .cost(order.getCost().doubleValue())
            .index(order.getIndex())
            .netPrice(order.getNetPrice().doubleValue())
            .pricePerAsset(order.getPricePerAsset().doubleValue())
            .type(order.getType())
            .build();
    }

    private Map<String, Double> backtest(BarSeries series, TradingRecord tradingRecord) {
        //FIXME Criterion should have a method to get name. This map population is pathetic.
        Map<String, Double> backtestresultsMap = new LinkedHashMap<>();
        backtestresultsMap.put("AverageProfitableTrades",
                calculateCriterion(new AverageProfitableTradesCriterion(), series, tradingRecord));
        backtestresultsMap.put("AverageProfit",
                calculateCriterion(new AverageProfitCriterion(), series, tradingRecord));
        backtestresultsMap.put("BuyAndHold",
                calculateCriterion(new BuyAndHoldCriterion(), series, tradingRecord));
        backtestresultsMap.put("LinearTransactionCost",
                calculateCriterion(new LinearTransactionCostCriterion(5000, 0.005), series, tradingRecord));
        backtestresultsMap.put("MaximumDrawdown",
                calculateCriterion(new MaximumDrawdownCriterion(), series, tradingRecord));
        backtestresultsMap.put("NumberOfBars",
                calculateCriterion(new NumberOfBarsCriterion(), series, tradingRecord));
        backtestresultsMap.put("NumberOfTrades",
                calculateCriterion(new NumberOfTradesCriterion(), series, tradingRecord));
        backtestresultsMap.put("RewardRiskRatio",
                calculateCriterion(new RewardRiskRatioCriterion(), series, tradingRecord));
        backtestresultsMap.put("TotalProfit",
                calculateCriterion(new TotalProfitCriterion(), series, tradingRecord));
        backtestresultsMap.put("ProfitLoss",
                calculateCriterion(new ProfitLossCriterion(), series, tradingRecord));
        return backtestresultsMap;
    }


    private Double calculateCriterion(AnalysisCriterion criterion, BarSeries series, TradingRecord tradingRecord) {
        System.out.println("-- " + criterion.getClass().getSimpleName() + " --");
        return criterion.calculate(series, tradingRecord).doubleValue();
    }


}
