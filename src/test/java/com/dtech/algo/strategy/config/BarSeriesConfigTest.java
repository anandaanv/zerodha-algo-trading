package com.dtech.algo.strategy.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

class BarSeriesConfigTest {

    private static String jsonValue = "{\n" +
            "    \"interval\" : \"FifteenMinute\",\n" +
            "    \"seriesType\" : \"EQUITY\",\n" +
            "    \"instrumentType\" : \"EQ\",\n" +
            "    \"exchange\" : \"NSE\",\n" +
            "    \"instrument\" : \"SBIN\",\n" +
            "    \"name\" : \"sbin15min\",\n" +
            "    \"startDate\" : \"2020-06-13\",\n" +
            "    \"endDate\" : \"2020-09-13\"\n" +
            "  }";

    @Test
    public void testJson() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        BarSeriesConfig config = objectMapper.readValue(jsonValue, BarSeriesConfig.class);
        System.out.println(config);
    }

}