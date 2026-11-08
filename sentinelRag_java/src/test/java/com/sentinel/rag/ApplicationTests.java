package com.sentinel.rag;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Full application context test requires PostgreSQL daemon or Testcontainers (Phase 3.10)")
class ApplicationTests {

	@Test
	void contextLoads() {
	}

}
