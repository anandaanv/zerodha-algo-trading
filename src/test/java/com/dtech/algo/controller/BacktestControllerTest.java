package com.dtech.algo.controller;

import com.dtech.algo.backtest.BackTestingHandlerJson;
import com.dtech.algo.backtest.BacktestInput;
import com.dtech.algo.backtest.BacktestResult;
import com.dtech.algo.exception.StrategyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class BacktestControllerTest {

    @Mock
    private BackTestingHandlerJson backTestingHandler;

    @InjectMocks
    private BacktestController backtestController;


    private ObjectMapper objectMapper = getObjectMapper();

    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Test
    void runBacktest() throws JsonProcessingException, StrategyException {
        BacktestInput backtestInput = objectMapper.readValue(backTestInput, BacktestInput.class);
        BacktestResult backtestResult = BacktestResult.builder().build();
        Mockito.doReturn(backtestResult)
                .when(backTestingHandler).execute(backtestInput);
        BacktestResult result = backtestController.runBacktest(backtestInput);
        Mockito.verify(backTestingHandler, Mockito.atLeast(1)).execute(backtestInput);
        assertEquals(backtestResult, result);
    }

    private String backTestInput = "{\n" +
            "  \"barSeriesConfigs\" : [ {\n" +
            "    \"interval\" : \"FifteenMinute\",\n" +
            "    \"seriesType\" : \"EQUITY\",\n" +
            "    \"instrumentType\" : \"EQ\",\n" +
            "    \"exchange\" : \"NSE\",\n" +
            "    \"instrument\" : \"SBIN\",\n" +
            "    \"name\" : \"sbin15min\",\n" +
            "    \"startDate\" : \"2020-06-13\",\n" +
            "    \"endDate\" : \"2020-09-13\"\n" +
            "  } ],\n" +
            "  \"barSeriesName\" : \"sbin\",\n" +
            "  \"strategyConfig\" : {\n" +
            "    \"strategyName\" : \"rsi-strategy\",\n" +
            "    \"direction\" : \"Buy\",\n" +
            "    \"constants\" : {\n" +
            "      \"longSmaBarCount\" : \"200\",\n" +
            "      \"rsiBarCount\" : \"2\",\n" +
            "      \"ninetyFive\" : \"95\",\n" +
            "      \"shortSmaBarCount\" : \"5\",\n" +
            "      \"five\" : \"5\"\n" +
            "    },\n" +
            "    \"indicators\" : [ {\n" +
            "      \"key\" : \"close-price-1\",\n" +
            "      \"indicatorName\" : \"close-price-indicator\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"default\",\n" +
            "        \"type\" : \"BarSeries\"\n" +
            "      } ]\n" +
            "    }, {\n" +
            "      \"key\" : \"short-sma\",\n" +
            "      \"indicatorName\" : \"s-m-a-indicator\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"close-price-1\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"shortSmaBarCount\",\n" +
            "        \"type\" : \"Integer\"\n" +
            "      } ]\n" +
            "    }, {\n" +
            "      \"key\" : \"long-sma\",\n" +
            "      \"indicatorName\" : \"s-m-a-indicator\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"close-price-1\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"longSmaBarCount\",\n" +
            "        \"type\" : \"Integer\"\n" +
            "      } ]\n" +
            "    }, {\n" +
            "      \"key\" : \"rsi-2\",\n" +
            "      \"indicatorName\" : \"r-s-i-indicator\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"close-price-1\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"rsiBarCount\",\n" +
            "        \"type\" : \"Integer\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"rules\" : [ {\n" +
            "      \"key\" : \"shortSmaUnderLongSma\",\n" +
            "      \"ruleName\" : \"under-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"short-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"long-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    }, {\n" +
            "      \"key\" : \"rsiCrossedUpFive\",\n" +
            "      \"ruleName\" : \"crossed-up-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"rsi-2\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"ninetyFive\",\n" +
            "        \"type\" : \"Number\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    }, {\n" +
            "      \"key\" : \"shortSmaUnderClosePrice\",\n" +
            "      \"ruleName\" : \"under-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"short-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"close-price-1\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    }, {\n" +
            "      \"key\" : \"shortSmaOverLongSma\",\n" +
            "      \"ruleName\" : \"over-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"short-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"long-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    }, {\n" +
            "      \"key\" : \"rsiCrossdownFive\",\n" +
            "      \"ruleName\" : \"crossed-down-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"rsi-2\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"five\",\n" +
            "        \"type\" : \"Number\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    }, {\n" +
            "      \"key\" : \"shortSmaOverClosePrice\",\n" +
            "      \"ruleName\" : \"over-indicator-rule\",\n" +
            "      \"inputs\" : [ {\n" +
            "        \"name\" : \"short-sma\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      }, {\n" +
            "        \"name\" : \"close-price-1\",\n" +
            "        \"type\" : \"Indicator\"\n" +
            "      } ],\n" +
            "      \"followUpRules\" : null\n" +
            "    } ],\n" +
            "    \"entry\" : [ \"shortSmaOverLongSma\", \"AND\", \"rsiCrossdownFive\", \"AND\", \"shortSmaOverClosePrice\" ],\n" +
            "    \"exit\" : [ \"shortSmaUnderLongSma\", \"AND\", \"rsiCrossedUpFive\", \"AND\", \"shortSmaUnderClosePrice\" ]\n" +
            "  }\n" +
            "}";
}