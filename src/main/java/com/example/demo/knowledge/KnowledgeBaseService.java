package com.example.demo.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
	private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
	private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
	private static final int MAX_FILES = 1_000;
	private static final Set<String> EXTENSIONS = Set.of("md", "txt", "csv", "json", "yaml", "yml",
			"properties", "java", "xml", "sql");
	private static final Pattern LATIN_WORD = Pattern.compile("[a-z0-9_\\-]{2,}");
	private final Path root;

	public KnowledgeBaseService(@Value("${assistant.knowledge-base.path:/var/lib/demo-assistant/knowledge}") String path) {
		this.root = Path.of(path).toAbsolutePath().normalize();
	}

	public String search(String query, int requestedLimit) {
		if (query == null || query.isBlank()) throw new IllegalArgumentException("知识库问题不能为空");
		int limit = Math.max(1, Math.min(8, requestedLimit));
		long startedAt = System.nanoTime();
		if (!Files.isDirectory(root)) {
			log.info("知识库目录不存在：{}", root);
			return "个人知识库尚未建立。请把 Markdown、TXT、CSV、JSON、YAML、Java、XML 或 SQL 文件放入：" + root;
		}
		Set<String> tokens = tokens(query);
		List<Hit> hits = new ArrayList<>();
		int fileCount = 0;
		try (var stream = Files.walk(root, 6)) {
			for (Path file : stream.filter(Files::isRegularFile).limit(MAX_FILES).toList()) {
				if (!supported(file) || Files.size(file) > MAX_FILE_BYTES) continue;
				fileCount++;
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String relative = root.relativize(file).toString();
				for (String chunk : chunks(content)) {
					int score = score(tokens, (relative + "\n" + chunk).toLowerCase(Locale.ROOT));
					if (score > 0) hits.add(new Hit(relative, compact(chunk), score));
				}
			}
		} catch (Exception error) {
			log.error("读取个人知识库失败：目录={}，原因={}", root, error.getMessage(), error);
			throw new IllegalStateException("读取个人知识库失败", error);
		}
		hits.sort(Comparator.comparingInt(Hit::score).reversed());
		List<Hit> selected = hits.stream().limit(limit).toList();
		long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
		log.info("知识库检索完成：文件数量={}，命中数量={}，返回数量={}，耗时={}毫秒",
				fileCount, hits.size(), selected.size(), durationMs);
		if (selected.isEmpty()) return "个人知识库中没有找到与该问题相关的内容。";
		StringBuilder result = new StringBuilder("知识库检索结果：\n");
		for (int i = 0; i < selected.size(); i++) {
			Hit hit = selected.get(i);
			result.append("\n[").append(i + 1).append("] 文件：").append(hit.file())
					.append("\n").append(hit.excerpt()).append('\n');
		}
		return result.toString().trim();
	}

	public Path root() { return root; }

	public Path add(String requestedName, String content) {
		if (content == null || content.isBlank()) throw new IllegalArgumentException("知识内容不能为空");
		String base = requestedName == null ? "" : requestedName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
		if (base.isBlank()) base = "knowledge-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
		if (!hasSupportedExtension(base)) base += ".txt";
		if (base.length() > 120) base = base.substring(base.length() - 120);
		try {
			Files.createDirectories(root);
			Path destination = root.resolve(base).normalize();
			if (!destination.startsWith(root)) throw new IllegalArgumentException("知识文件名无效");
			if (Files.exists(destination)) destination = uniqueDestination(base);
			Files.writeString(destination, content, StandardCharsets.UTF_8);
			log.info("知识库文件已写入：文件={}，字数={}", destination.getFileName(), content.length());
			return destination;
		} catch (java.io.IOException error) {
			log.error("写入知识库失败：文件={}，原因={}", base, error.getMessage(), error);
			throw new IllegalStateException("写入个人知识库失败", error);
		}
	}

	private Path uniqueDestination(String base) {
		int dot = base.lastIndexOf('.');
		String stem = dot > 0 ? base.substring(0, dot) : base;
		String extension = dot > 0 ? base.substring(dot) : "";
		return root.resolve(stem + "-" + UUID.randomUUID().toString().substring(0, 8) + extension).normalize();
	}

	private static boolean hasSupportedExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static boolean supported(Path file) {
		return hasSupportedExtension(file.getFileName().toString());
	}

	private static List<String> chunks(String content) {
		String normalized = content.replace("\r", "").trim();
		if (normalized.isEmpty()) return List.of();
		List<String> result = new ArrayList<>();
		for (String paragraph : normalized.split("\\n\\s*\\n")) {
			for (int start = 0; start < paragraph.length(); start += 900) {
				result.add(paragraph.substring(start, Math.min(paragraph.length(), start + 1_200)));
			}
		}
		return result;
	}

	private static Set<String> tokens(String query) {
		String value = query.toLowerCase(Locale.ROOT);
		Set<String> result = new HashSet<>();
		Matcher matcher = LATIN_WORD.matcher(value);
		while (matcher.find()) result.add(matcher.group());
		List<String> chinese = value.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
				.mapToObj(Character::toString).toList();
		result.addAll(chinese);
		for (int i = 0; i + 1 < chinese.size(); i++) result.add(chinese.get(i) + chinese.get(i + 1));
		return result;
	}

	private static int score(Set<String> tokens, String content) {
		int score = 0;
		for (String token : tokens) {
			int at = -1;
			int occurrences = 0;
			while ((at = content.indexOf(token, at + 1)) >= 0 && occurrences < 8) occurrences++;
			score += occurrences * (token.length() > 1 ? 3 : 1);
		}
		return score;
	}

	private static String compact(String value) {
		String compact = value.replaceAll("\\s+", " ").trim();
		return compact.length() <= 1_000 ? compact : compact.substring(0, 1_000) + "…";
	}

	private record Hit(String file, String excerpt, int score) { }
}
