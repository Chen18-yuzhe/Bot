package com.example.demo.agent;

import java.time.Duration;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for creating, controlling and auditing durable Agent tasks. */
@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {
	private final AgentLoopService service;
	private final List<AgentTaskType> taskTypes;

	public AgentTaskController(AgentLoopService service, List<AgentTaskType> taskTypes) {
		this.service = service;
		this.taskTypes = taskTypes;
	}

	@GetMapping("/types")
	public List<TaskTypeView> taskTypes() {
		return taskTypes.stream()
				.sorted(java.util.Comparator.comparingInt(AgentTaskType::routingPriority).reversed())
				.map(type -> new TaskTypeView(type.name(), type.loopMode().name(),
						type.routingPriority(), type.allowedTools().stream().sorted().toList()))
				.toList();
	}

	@PostMapping
	public AgentTask create(@RequestBody CreateRequest request) {
		if (request == null) throw new IllegalArgumentException("请求体不能为空");
		return service.submit(request.sessionId(), request.taskType(), request.goal(),
				request.delaySeconds() == null ? Duration.ZERO : Duration.ofSeconds(Math.max(0, request.delaySeconds())));
	}
	@GetMapping
	public List<AgentTask> list(@RequestParam(required = false) String sessionId) { return service.list(sessionId); }
	@GetMapping("/{taskId}")
	public AgentTask get(@PathVariable String taskId) { return service.get(taskId); }
	@GetMapping("/{taskId}/trace")
	public List<AgentTraceEvent> trace(@PathVariable String taskId) { return service.trace(taskId); }
	@PostMapping("/{taskId}/pause")
	public AgentTask pause(@PathVariable String taskId) { return service.pause(taskId); }
	@PostMapping("/{taskId}/resume")
	public AgentTask resume(@PathVariable String taskId) { return service.resume(taskId); }
	@PostMapping("/{taskId}/retry")
	public AgentTask retry(@PathVariable String taskId) { return service.retry(taskId); }
	@PostMapping("/{taskId}/human-input")
	public AgentTask humanInput(@PathVariable String taskId, @RequestBody HumanInput request) {
		return service.submitHumanInput(taskId, request == null ? null : request.input());
	}
	@DeleteMapping("/{taskId}")
	public java.util.Map<String, Boolean> delete(@PathVariable String taskId) {
		service.delete(taskId);
		return java.util.Map.of("deleted", true);
	}

	public record CreateRequest(String sessionId, String taskType, String goal, Long delaySeconds) { }
	public record HumanInput(String input) { }
	public record TaskTypeView(String name, String loopMode, int routingPriority, List<String> allowedTools) { }
}
