package com.example.bot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Provides a friendly landing route for the private administration console.
 */
@Controller
public class AdminEntryController {

	@GetMapping({ "/", "/index.html" })
	public String home() {
		return "redirect:/api/admin/ilink/login";
	}
}
