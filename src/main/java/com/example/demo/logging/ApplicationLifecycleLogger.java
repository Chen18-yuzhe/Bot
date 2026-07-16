package com.example.demo.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLifecycleLogger {

	private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycleLogger.class);

	@EventListener
	public void onContextClosed(ContextClosedEvent event) {
		log.info("应用正在关闭");
	}
}
