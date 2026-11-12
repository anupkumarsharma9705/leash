package com.leash;

import org.springframework.boot.SpringApplication;

public class TestLeashApplication {

  public static void main(String[] args) {
    SpringApplication.from(LeashApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
