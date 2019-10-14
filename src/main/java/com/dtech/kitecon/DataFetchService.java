package com.dtech.kitecon;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Profile;
import com.zerodhatech.models.User;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;

@Service
public class DataFetchService {

  private KiteConnect kiteConnect;

  public String getProfile() throws IOException, KiteException {
    Profile profile = kiteConnect.getProfile();
    return profile.userName;
  }

  //TODO export the credentials to configuration.
  @PostConstruct
  public void connect() throws KiteException, IOException {
    this.kiteConnect = new KiteConnect("1g19o5ohebi8fj3l");

    //If you wish to enable debug logs send true in the constructor, this will log request and response.
    //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", true);

    // If you wish to set proxy then pass proxy as a second parameter in the constructor with api_key. syntax:- new KiteConnect("xxxxxxyyyyyzzz", proxy).
    //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", userProxy, false);

    // Set userId
    kiteConnect.setUserId("ZQ5356");

    // Get login url
    String url = kiteConnect.getLoginURL();

    User user =  kiteConnect.generateSession("Lq3vAw16EQmqlQeehoYPw9qZuFqDyobb", "47hfn5uwrb138506whg0lk26w6pxiadi");
    kiteConnect.setAccessToken(user.accessToken);
    kiteConnect.setPublicToken(user.publicToken);
  }

}
