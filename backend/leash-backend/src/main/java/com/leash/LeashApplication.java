package com.leash;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LeashApplication {

  public static void main(String[] args) {
    // Leash operates in UTC. Pinning the JVM default also keeps JDBC's startup TimeZone
    // parameter valid on hosts that expose legacy zone IDs such as Asia/Calcutta.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(LeashApplication.class, args);
  }
}
