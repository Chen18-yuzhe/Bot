package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.tools.AssistantToolRegistry;

@SpringBootTest
class DemoApplicationTests {
	@Autowired AssistantToolRegistry registry;

	@Test
	void contextLoads() {
		assertEquals(Set.of("query_weather", "generate_image", "edit_image", "search_web",
				"search_knowledge_base", "add_to_knowledge_base", "manage_reminder", "process_document",
				"transcribe_audio", "synthesize_speech", "manage_voice", "query_time",
				"scientific_calculator", "calculate_body_fat", "lookup_daily_info", "travel_discovery",
				"travel_plan", "travel_ticket", "travel_ticket_market", "travel_order",
				"travel_checkout", "travel_purchase_entry", "travel_purchase_links",
				"travel_ticket_platform_entries", "travel_transport",
				"travel_fliggy_search"),
				Set.copyOf(registry.names()));
	}

}
