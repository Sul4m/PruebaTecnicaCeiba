package com.vidacruz.clinical_assistant_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "assistant.security.api-key=test-key")
class ClinicalAssistantServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
