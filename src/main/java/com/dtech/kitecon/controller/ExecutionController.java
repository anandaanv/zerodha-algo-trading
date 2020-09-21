package com.dtech.kitecon.controller;

import com.dtech.kitecon.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ExecutionController {

  private final ExecutionService executionService;


  @GetMapping("/start/{strategyName}/{instrument}/{direction}")
  @ResponseBody
  public String startStrategy(@PathVariable String strategyName,
      @PathVariable String instrument, @PathVariable String direction) throws InterruptedException {
    return executionService.startStrategy(strategyName, instrument, direction);
  }


}
