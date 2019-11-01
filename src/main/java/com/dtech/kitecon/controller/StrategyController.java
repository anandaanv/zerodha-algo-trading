package com.dtech.kitecon.controller;

import com.dtech.kitecon.strategy.SimpleMovingAverageBacktesting;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class StrategyController {

    private final SimpleMovingAverageBacktesting simpleMovingAverageBacktesting;

    @GetMapping("/test/{instrument}")
    public void backtestStrategy(@PathVariable long instrument) throws InterruptedException {
        simpleMovingAverageBacktesting.execute(instrument);
    }

}
