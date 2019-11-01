package com.dtech.kitecon.controller;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Getter
public class KiteConnectConfig {

    private KiteConnect kiteConnect;

    public final void initialize(String requestToken) throws KiteException, IOException {
        this.kiteConnect = new KiteConnect("1g19o5ohebi8fj3l");

        //If you wish to enable debug logs send true in the constructor, this will log request and response.
        //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", true);

        // If you wish to set proxy then pass proxy as a second parameter in the constructor with api_key. syntax:- new KiteConnect("xxxxxxyyyyyzzz", proxy).
        //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", userProxy, false);

        // Set userId
        kiteConnect.setUserId("ZQ5356");

        // Get login url
        String url = kiteConnect.getLoginURL();

        User user = kiteConnect.generateSession(requestToken, "47hfn5uwrb138506whg0lk26w6pxiadi");
        kiteConnect.setAccessToken(user.accessToken);
        kiteConnect.setPublicToken(user.publicToken);
    }

}
