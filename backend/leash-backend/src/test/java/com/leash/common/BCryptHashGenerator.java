package com.leash.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class BCryptHashGenerator {

  @Test
  void generateHash() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    String hash = encoder.encode("admin123");
    System.out.println("BCrypt hash for 'admin123': " + hash);
    assertTrue(encoder.matches("admin123", hash));
  }
}
