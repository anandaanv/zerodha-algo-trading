package com.dtech.kitecon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = {"com.dtech"})
@EnableJpaRepositories(basePackages = {"com.dtech"})
@EntityScan(basePackages = {"com.dtech"})
public class KiteconApplication {

  public static void main(String[] args) {
    SpringApplication.run(KiteconApplication.class, args);
  }

}
