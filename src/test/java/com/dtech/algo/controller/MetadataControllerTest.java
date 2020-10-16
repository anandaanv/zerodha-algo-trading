package com.dtech.algo.controller;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorInfo;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.rules.RuleInfo;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.helper.ComponentHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class MetadataControllerTest {

    @Mock
    private IndicatorRegistry indicatorRegistry;

    @Mock
    private RuleRegistry ruleRegistry;

    @InjectMocks
    private MetadataController metadataController;

    private ComponentHelper componentHelper = new ComponentHelper(null, null, null, null);

    @Test
    void getRuleDetails() throws StrategyException {
        String name = "constant-indicator";
        Mockito.doReturn(Collections.singletonList(name))
                .when(indicatorRegistry).getAllObjectNames();
        Mockito.doReturn(componentHelper.getConstantIndicatorInfo("a", "b", "c"))
                .when(indicatorRegistry).getObjectInfo(name);
        Map<String, IndicatorInfo> indicatorDetails = metadataController.getIndicatorDetails();
        assertEquals(indicatorDetails.get(name).getName(), "c");
    }

    @Test
    void getIndicatorDetails() throws StrategyException {
        String name = "and-rule";
        Mockito.doReturn(Collections.singletonList(name))
                .when(ruleRegistry).getAllObjectNames();
        Mockito.doReturn(componentHelper.getGenericRuleInfo(name))
                .when(ruleRegistry).getObjectInfo(name);
        Map<String, RuleInfo> indicatorDetails = metadataController.getRuleDetails();
        assertEquals(indicatorDetails.get(name).getName(), name);
    }
}