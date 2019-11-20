package com.dtech.kitecon;

import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContextBootstrapper;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebTestContextBootstrapper;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@RunWith(SpringRunner.class)
public class KiteconApplicationTests {

  @Autowired
  private FifteenMinuteCandleRepository fifteenMinuteCandleRepository;

  @Test
  public void contextLoads() {
  }

}
