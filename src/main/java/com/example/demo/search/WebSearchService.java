package com.example.demo.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.common.SearchOptions;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;
import com.example.demo.chat.DashScopeModelFallback;
import com.example.demo.guard.UpstreamGuard;

@Service
public class WebSearchService {
	/** A machine-readable marker for callers that require an externally sourced URL. */
	public static final String NO_VERIFIED_SOURCES = "NO_VERIFIED_SOURCES";
	private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
	private final String apiKey;
	private final String baseUrl;
	private final DashScopeModelFallback modelFallback;
	private final UpstreamGuard upstreamGuard;

	public WebSearchService(@Value("${aliyun.dashscope.api-key:}") String apiKey,
			@Value("${aliyun.dashscope.base-url:}") String baseUrl,
			DashScopeModelFallback modelFallback, UpstreamGuard upstreamGuard) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.modelFallback = modelFallback;
		this.upstreamGuard = upstreamGuard;
	}

	/** 联网搜索按 token 计费；连续失败时熔断快速失败，避免烧配额。 */
	public String search(String query) {
		if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索内容不能为空");
		if (apiKey == null || apiKey.isBlank()) {
			throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置 DASHSCOPE_API_KEY");
		}
		return upstreamGuard.call("web-search", () -> doSearch(query));
	}

	private String doSearch(String query) {
		long startedAt = System.nanoTime();
		configureEndpoint();
		try {
			MultiModalMessage user = MultiModalMessage.builder().role(Role.USER.getValue())
					.content(List.of(Map.of("text", "请联网搜索并准确回答下面的问题。优先使用近期、权威来源，不确定时明确说明。问题：" + query.trim())))
					.build();
			SearchOptions options = SearchOptions.builder().forcedSearch(true).enableSource(true)
					.enableCitation(true).citationFormat("[<number>]").build();
			var result = modelFallback.call("联网搜索", model -> {
				MultiModalConversationParam request = MultiModalConversationParam.builder()
						.apiKey(apiKey).model(model).messages(List.of(user)).enableSearch(true)
						.searchOptions(options).enableThinking(false).maxTokens(1800).build();
				return new MultiModalConversation().call(request);
			});
			String answer = result.getOutput().getChoices().get(0).getMessage().getContent().stream()
					.filter(item -> item.containsKey("text")).map(item -> String.valueOf(item.get("text")))
					.findFirst().orElse("").trim();
			List<String> sources = new ArrayList<>();
			if (result.getOutput().getSearchInfo() != null
					&& result.getOutput().getSearchInfo().getSearchResults() != null) {
				result.getOutput().getSearchInfo().getSearchResults().stream().limit(6).forEach(source -> {
					if (source.getUrl() != null && !source.getUrl().isBlank()) {
						String title = source.getTitle() == null || source.getTitle().isBlank()
								? source.getSiteName() : source.getTitle();
						sources.add("- " + (title == null ? "来源" : title) + "：" + source.getUrl());
					}
				});
			}
			if (sources.isEmpty()) {
				// The model's prose is not evidence for a purchasable page. Returning a
				// marker lets the Agent stop rather than repeatedly reformulating the
				// same search for several minutes.
				return NO_VERIFIED_SOURCES + "\n联网搜索未返回可验证的来源链接。";
			}
			answer += "\n\n来源：\n" + String.join("\n", sources);
			long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
			log.info("联网搜索完成：查询字数={}，来源数量={}，耗时={}毫秒", query.length(), sources.size(), durationMs);
			return answer.isBlank() ? "联网搜索没有返回有效结果。" : answer;
		} catch (ApplicationException error) {
			throw error;
		} catch (Exception error) {
			log.error("联网搜索失败：查询字数={}，原因={}", query.length(), error.getMessage(), error);
			throw new ApplicationException(HttpStatus.BAD_GATEWAY, "联网搜索失败，请稍后再试", error);
		}
	}

	private void configureEndpoint() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
			Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
		}
	}
}
