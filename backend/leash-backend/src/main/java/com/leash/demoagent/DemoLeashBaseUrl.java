package com.leash.demoagent;

/** Supplies the demo agent's view of the Leash base URL. */
@FunctionalInterface
public interface DemoLeashBaseUrl {
  String current();
}
