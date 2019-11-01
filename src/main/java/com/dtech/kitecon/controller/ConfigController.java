package com.dtech.kitecon.controller;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@Controller
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ConfigController {

    private final KiteConnectConfig kiteConnectConfig;

    @GetMapping("/app")
    public String fetchData(@RequestParam("request_token") String token) throws IOException, KiteException {
        kiteConnectConfig.initialize(token);
        return "success";
    }

}
