package com.example.demo.document;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import com.example.demo.chat.MediaContent;
import com.example.demo.chat.QwenService;

class DocumentSummaryServiceTests {
	@Test
	void shouldExtractAndReturnReadableDocx() throws Exception {
		byte[] source;
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText("项目目标是完成 Word 文档总结功能。");
			document.write(output);
			source = output.toByteArray();
		}
		QwenService qwen = mock(QwenService.class);
		when(qwen.chatWithContext(anyString(), anyString())).thenReturn("核心结论：已完成文档总结功能。");
		GeneratedDocument result = new DocumentSummaryService(qwen).summarize(
				new MediaContent(MediaContent.MediaType.DOCUMENT, source, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
				"请总结");
		try (XWPFDocument output = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
			assertTrue(output.getParagraphs().stream().anyMatch(p -> p.getText().contains("核心结论")));
		}
	}

	@Test
	void shouldExtractPdf() throws Exception {
		byte[] source;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.showText("pdf-quarterly-report");
				content.endText();
			}
			document.save(output);
			source = output.toByteArray();
		}
		assertSourceExtracted(source, "application/pdf", "pdf-quarterly-report");
	}

	@Test
	void shouldExtractSpreadsheet() throws Exception {
		byte[] source;
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			workbook.createSheet("report").createRow(0).createCell(0).setCellValue("spreadsheet-budget");
			workbook.write(output);
			source = output.toByteArray();
		}
		assertSourceExtracted(source,
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "spreadsheet-budget");
	}

	@Test
	void shouldExtractPresentation() throws Exception {
		byte[] source;
		try (XMLSlideShow slides = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			slides.createSlide().createTextBox().setText("presentation-roadmap");
			slides.write(output);
			source = output.toByteArray();
		}
		assertSourceExtracted(source,
				"application/vnd.openxmlformats-officedocument.presentationml.presentation", "presentation-roadmap");
	}

	@Test
	void shouldExtractPlainText() {
		assertSourceExtracted("plain-meeting-notes".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				"text/plain", "plain-meeting-notes");
	}

	private static void assertSourceExtracted(byte[] source, String mimeType, String expected) {
		QwenService qwen = mock(QwenService.class);
		when(qwen.chatWithContext(anyString(), anyString())).thenReturn("总结完成");
		new DocumentSummaryService(qwen).summarize(
				new MediaContent(MediaContent.MediaType.DOCUMENT, source, mimeType), "请总结");
		verify(qwen).chatWithContext(anyString(), contains(expected));
	}
}
