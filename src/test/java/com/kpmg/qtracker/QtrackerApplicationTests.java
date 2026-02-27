package com.kpmg.qtracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class QtrackerApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void draftInitiateReminderBeansAreNotRegistered() {
		assertFalse(applicationContext.containsBean("draftInitiateReminderScheduler"));
		assertFalse(applicationContext.containsBean("draftInitiateReminderService"));
	}

}


