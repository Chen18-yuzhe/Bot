package com.example.demo.audio;
import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.*;
import com.alibaba.dashscope.utils.Constants;
import com.example.demo.ApplicationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AudioService {
  private static final Logger log = LoggerFactory.getLogger(AudioService.class);
  private final String apiKey, asrModel, ttsModel, voice, baseUrl;
  public AudioService(@Value("${aliyun.dashscope.api-key:}") String key,
      @Value("${aliyun.dashscope.audio.asr-model:qwen3-asr-flash}") String asr,
      @Value("${aliyun.dashscope.audio.tts-model:cosyvoice-v2}") String tts,
      @Value("${aliyun.dashscope.audio.voice:longxiaochun_v2}") String v,
      @Value("${aliyun.dashscope.base-url:}") String url) { apiKey=key; asrModel=asr; ttsModel=tts; voice=v; baseUrl=url; }
  public String recognize(File file) {
    requireKey(); if (file == null || !file.isFile()) throw new IllegalArgumentException("Audio file does not exist"); configureEndpoint();
    try {
      log.info("Calling DashScope ASR: model={}, file={}, bytes={}", asrModel, file.getName(), file.length());
      String dataUrl = "data:" + detectAudioMimeType(file) + ";base64,"
          + Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
      var message = MultiModalMessage.builder().role(Role.USER.getValue()).content(List.of(Map.of("audio", dataUrl))).build();
      var request = MultiModalConversationParam.builder().apiKey(apiKey).model(asrModel).messages(List.of(message)).build();
      var result = new MultiModalConversation().call(request);
      String transcript = result.getOutput().getChoices().get(0).getMessage().getContent().stream().filter(p -> p.containsKey("text")).map(p -> p.get("text")).findFirst().map(Object::toString).orElse("").trim();
      if (transcript.isBlank()) throw new IllegalStateException("DashScope returned an empty transcript");
      log.info("DashScope ASR succeeded: characters={}", transcript.length());
      return transcript;
    } catch (Exception e) {
      log.error("DashScope ASR failed: model={}, file={}, bytes={}, error={}", asrModel, file.getName(), file.length(), e.getMessage(), e);
      throw new ApplicationException(HttpStatus.BAD_GATEWAY, "Speech recognition failed: " + safeError(e), e);
    }
  }
  public byte[] synthesize(String text) {
    return synthesize(text, voice);
  }

  public byte[] synthesize(String text, String selectedVoice) {
    requireKey(); if (text == null || text.isBlank()) throw new IllegalArgumentException("Text cannot be blank");
    try {
      String effectiveVoice = selectedVoice == null || selectedVoice.isBlank() ? voice : selectedVoice;
      var p = SpeechSynthesisParam.builder().apiKey(apiKey).model(ttsModel).voice(effectiveVoice)
          .format(SpeechSynthesisAudioFormat.WAV_16000HZ_MONO_16BIT).build();
      ByteBuffer b = new SpeechSynthesizer(p, null).call(text);
      byte[] out = new byte[b.remaining()]; b.get(out); return out;
    }
    catch (Exception e) { throw new ApplicationException(HttpStatus.BAD_GATEWAY, "Speech synthesis failed", e); }
  }
  private void requireKey() { if (apiKey == null || apiKey.isBlank()) throw new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "Configure DASHSCOPE_API_KEY first"); }
  private void configureEndpoint() {
    if (baseUrl != null && !baseUrl.isBlank()) {
      String endpoint = baseUrl.trim().replace("/compatible-mode/v1", "/api/v1");
      Constants.baseHttpApiUrl = endpoint.endsWith("/") ? endpoint : endpoint + "/";
    }
  }
  private static String safeError(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) return error.getClass().getSimpleName();
    return message.length() > 300 ? message.substring(0, 300) : message;
  }
  private static String detectAudioMimeType(File file) {
    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".wav")) return "audio/wav";
    if (name.endsWith(".mp3")) return "audio/mpeg";
    if (name.endsWith(".m4a")) return "audio/mp4";
    if (name.endsWith(".flac")) return "audio/flac";
    if (name.endsWith(".ogg")) return "audio/ogg";
    if (name.endsWith(".opus")) return "audio/opus";
    if (name.endsWith(".aac")) return "audio/aac";
    if (name.endsWith(".amr")) return "audio/amr";
    try {
      String detected = Files.probeContentType(file.toPath());
      return detected != null && detected.startsWith("audio/") ? detected : "application/octet-stream";
    } catch (java.io.IOException ignored) {
      return "application/octet-stream";
    }
  }
}
