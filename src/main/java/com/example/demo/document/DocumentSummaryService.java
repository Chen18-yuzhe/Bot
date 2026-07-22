package com.example.demo.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.demo.chat.MediaContent;
import com.example.demo.chat.QwenService;

@Service
public class DocumentSummaryService {
	private static final Logger log = LoggerFactory.getLogger(DocumentSummaryService.class);
	private static final int MAX_SOURCE_CHARS = 50_000;
	private final QwenService qwenService;

	public DocumentSummaryService(QwenService qwenService) {
		this.qwenService = qwenService;
	}

	public GeneratedDocument summarize(MediaContent media, String instruction) {
		long startedAt = System.nanoTime();
		try {
			String source = extractText(media);
			if (source.isBlank()) throw new IllegalArgumentException("文件中没有可读取的文字内容");
			String request = instruction == null || instruction.isBlank() ? "请总结这份文件" : instruction.trim();
			String context = """
					以下是用户上传文件中提取出的文字内容。请严格依据原文处理，不要编造；保留关键结论、事实、数字、日期和待办事项。
					文件内容：
					%s
					""".formatted(source);
			log.info("开始处理文档：类型={}，提取字数={}，指令字数={}", media.mimeType(), source.length(), request.length());
			String summary = qwenService.chatWithContext(request, context);
			GeneratedDocument result = new GeneratedDocument(createDocx(summary), "document-result.docx", summary);
			log.info("文档处理完成：结果字数={}，文件字节={}，耗时={}毫秒", summary.length(), result.bytes().length,
					(System.nanoTime() - startedAt) / 1_000_000L);
			return result;
		} catch (RuntimeException error) {
			log.warn("文档处理失败：类型={}，原因={}", media == null ? "unknown" : media.mimeType(), error.getMessage());
			throw error;
		}
	}

	public String extractText(MediaContent media) {
		try {
			String mimeType = media.mimeType();
			if ("application/pdf".equals(mimeType)) return limit(extractPdf(media.data()));
			if (mimeType.startsWith("text/") || "text/csv".equals(mimeType)) {
				return limit(new String(media.data(), java.nio.charset.StandardCharsets.UTF_8));
			}
			if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
				return limit(extractDocx(media.data()));
			}
			return limit(extractOffice(media.data()));
		} catch (Exception error) {
			throw new IllegalArgumentException("无法解析该文件，请确认格式有效且文件未加密", error);
		}
	}

	private String extractDocx(byte[] bytes) throws Exception {
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
			StringBuilder text = new StringBuilder();
			document.getParagraphs().forEach(p -> append(text, p.getText()));
			document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
					.forEach(cell -> append(text, cell.getText()))));
			return text.toString();
		}
	}

	private String extractPdf(byte[] bytes) throws Exception {
		try (org.apache.pdfbox.pdmodel.PDDocument document = Loader.loadPDF(bytes)) {
			return new PDFTextStripper().getText(document);
		}
	}

	private String extractOffice(byte[] bytes) throws Exception {
		try (POITextExtractor extractor = ExtractorFactory.createExtractor(new ByteArrayInputStream(bytes))) {
			return extractor.getText();
		}
	}

	private String limit(String text) {
		return text.length() <= MAX_SOURCE_CHARS ? text : text.substring(0, MAX_SOURCE_CHARS);
	}

	private static void append(StringBuilder text, String value) {
		if (value != null && !value.isBlank()) text.append(value.trim()).append('\n');
	}

	private byte[] createDocx(String summary) {
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			configureA4Page(document);
			XWPFParagraph title = document.createParagraph();
			title.setAlignment(ParagraphAlignment.CENTER);
			XWPFRun titleRun = title.createRun();
			titleRun.setText("文档内容总结");
			titleRun.setBold(true);
			titleRun.setFontFamily("Microsoft YaHei");
			titleRun.setFontSize(18);

			XWPFParagraph metadata = document.createParagraph();
			metadata.setAlignment(ParagraphAlignment.CENTER);
			XWPFRun metadataRun = metadata.createRun();
			metadataRun.setText("生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
			metadataRun.setFontFamily("Microsoft YaHei");
			metadataRun.setFontSize(9);
			metadataRun.setColor("666666");

			for (String line : summary.split("\\R")) {
				if (line.isBlank()) continue;
				XWPFParagraph paragraph = document.createParagraph();
				paragraph.setSpacingAfter(120);
				XWPFRun run = paragraph.createRun();
				run.setText(line.trim());
				run.setFontFamily("Microsoft YaHei");
				run.setFontSize(11);
			}
			document.write(output);
			return output.toByteArray();
		} catch (Exception error) {
			throw new IllegalStateException("生成总结 Word 文件失败", error);
		}
	}

	private static void configureA4Page(XWPFDocument document) {
		CTSectPr section = document.getDocument().getBody().addNewSectPr();
		CTPageSz pageSize = section.addNewPgSz();
		pageSize.setW(BigInteger.valueOf(11_906));
		pageSize.setH(BigInteger.valueOf(16_838));
		CTPageMar margins = section.addNewPgMar();
		margins.setTop(BigInteger.valueOf(1_440));
		margins.setBottom(BigInteger.valueOf(1_440));
		margins.setLeft(BigInteger.valueOf(1_440));
		margins.setRight(BigInteger.valueOf(1_440));
	}
}
