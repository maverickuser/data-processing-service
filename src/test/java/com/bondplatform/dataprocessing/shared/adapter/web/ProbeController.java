package com.bondplatform.dataprocessing.shared.adapter.web;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that let integration tests provoke each kind of framework error. It lives in
 * test sources, so it is never part of the deployed application.
 */
@RestController
@RequestMapping("/test-probe")
class ProbeController {

  @GetMapping(value = "/dated", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> dated(@RequestParam LocalDate tradeDate) {
    return Map.of("tradeDate", tradeDate.toString());
  }

  @PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> echo(@RequestBody Map<String, Object> body) {
    return body;
  }

  @GetMapping("/failing")
  Map<String, Object> failing() {
    throw new IllegalStateException("password=secret");
  }
}
