package com.example.bot.audio;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;

@RestController @RequestMapping("/api/audio")
public class AudioController {
  private final AudioService service;
  public AudioController(AudioService service) { this.service = service; }
  @PostMapping(value="/recognize", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public RecognitionResponse recognize(@RequestPart("file") MultipartFile file) throws Exception {
    String originalName = file.getOriginalFilename();
    String suffix = originalName != null && originalName.lastIndexOf('.') >= 0
        ? originalName.substring(originalName.lastIndexOf('.')) : ".bin";
    java.nio.file.Path temp = Files.createTempFile("audio-", suffix);
    try { file.transferTo(temp); return new RecognitionResponse(service.recognize(temp.toFile())); }
    finally { Files.deleteIfExists(temp); }
  }
  @PostMapping(value="/synthesize", consumes=MediaType.APPLICATION_JSON_VALUE, produces="audio/wav")
  public byte[] synthesize(@RequestBody SynthesisRequest request) { return service.synthesize(request.text()); }
  public record RecognitionResponse(String text) {}
  public record SynthesisRequest(String text) {}
}
