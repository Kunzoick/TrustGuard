package com.trustguard;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Application context requires database and Redis - enabled in B-003")
class TrustguardApplicationTests {

	@Test
	void contextLoads() {
	}

}
