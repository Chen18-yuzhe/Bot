package com.example.demo.chat;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final AssistantService assistantService;

	public ChatController(AssistantService assistantService) {
		this.assistantService = assistantService;
	}

	@PostMapping
	public ChatResponse chat(@RequestBody ChatRequest request) {
		if (request.question() == null || request.question().isBlank()) {
			throw new IllegalArgumentException("问题不能为空");
		}

		String answer = assistantService.chat(request.question().trim());
		return new ChatResponse(answer);
	}
}
