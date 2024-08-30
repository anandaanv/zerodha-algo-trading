package com.dtech.kitecon.controller;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.service.IndexSymbolUpdaterService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ConfigController {

  private final KiteConnectConfig kiteConnectConfig;
  private final IndexSymbolUpdaterService updaterService;

  @GetMapping("/app")
  @ResponseBody
  public String fetchData(@RequestParam("request_token") String token)
          throws IOException, KiteException, InterruptedException {
    kiteConnectConfig.initialize(token);
//    updaterService.updateSymbols();
    return "success";
  }

}
