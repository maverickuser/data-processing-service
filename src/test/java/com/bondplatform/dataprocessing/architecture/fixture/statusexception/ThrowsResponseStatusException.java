package com.bondplatform.dataprocessing.architecture.fixture.statusexception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Breaks the rule that application code never throws the framework's status exceptions. */
public class ThrowsResponseStatusException {

  void conflict() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "internal detail");
  }
}
