package com.dtech.kitecon.controller;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.service.IndexSymbolUpdaterService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ConfigController {

  private final KiteConnectConfig kiteConnectConfig;
  private final IndexSymbolUpdaterService updaterService;
  private final KiteTickerService tickerService;

  @Value("${kite.api.key}")
  private String kiteApiKey;

  @GetMapping("/kite-login")
  public RedirectView redirectToKiteAuth() {
    String kiteAuthUrl = String.format("https://kite.trade/connect/login?api_key=%s&v=3", kiteApiKey);
    return new RedirectView(kiteAuthUrl);
  }

  @GetMapping("/app")
  @ResponseBody
  public String fetchData(@RequestParam("request_token") String token)
          throws IOException, KiteException, InterruptedException {
    kiteConnectConfig.initialize(token);
    tickerService.init();
//    updaterService.updateSymbols();
    return "success";
  }

    @GetMapping("/update-token")
    public void updateToken(@RequestParam("request_token") String token)
            throws IOException, KiteException, InterruptedException {
        kiteConnectConfig.initialize(token);
        tickerService.init();
    }

}
