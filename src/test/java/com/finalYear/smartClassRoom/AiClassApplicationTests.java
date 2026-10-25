package com.finalYear.smartClassRoom;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires live PostgreSQL database connection; unit and integration tests run in dedicated stage test classes")
class AiClassApplicationTests {

	@Test
	void contextLoads() {
	}

}
