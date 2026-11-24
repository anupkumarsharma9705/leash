package com.leash.demoagent;

/** A failed call from the demo agent to the public Leash API. */
public class DemoClientException extends RuntimeException {

  private final Integer httpStatus;
  private final String code;

  public DemoClientException(Integer httpStatus, String code, String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
  }

  public DemoClientException(String code, String message, Throwable cause) {
    super(message, cause);
    this.httpStatus = null;
    this.code = code;
  }

  public Integer httpStatus() {
    return httpStatus;
  }

  public String code() {
    return code;
  }
}
