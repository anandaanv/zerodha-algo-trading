package com.dtech.kitecon.controller;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Controller
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ConfigController {

    private final KiteConnectConfig kiteConnectConfig;

    @GetMapping("/app")
    @ResponseBody
    public String fetchData(@RequestParam("request_token") String token) throws IOException, KiteException {
        kiteConnectConfig.initialize(token);
        return "success";
    }

}
