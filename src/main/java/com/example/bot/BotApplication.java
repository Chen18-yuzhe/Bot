package com.example.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableScheduling
public class BotApplication {
	@Bean
	ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

	public static void main(String[] args) {
		log.info("正在启动应用");
		SpringApplication.run(BotApplication.class, args);
		log.info("应用启动成功");
	}

}
