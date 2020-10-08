package com.dtech.kitecon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;

@SpringBootApplication
@ComponentScan(basePackages = {"com.dtech"})
public class KiteconApplication {

  public static void main(String[] args) {
    SpringApplication.run(KiteconApplication.class, args);
  }

}
