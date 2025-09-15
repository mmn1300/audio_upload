package com.example.audio_upload_web.compression.web;

import com.example.audio_upload_web.compression.service.CompressionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/compression")
public class CompressionRestController {

    @Autowired
    private CompressionService compressionService;


    @PostMapping(consumes= MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> compression(MultipartFile file) throws IOException {
        // 검증
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "빈 파일"));
        String contentType = file.getContentType(); // 예: audio/webm
        if (!contentType.endsWith("/zip")) {
            return ResponseEntity.badRequest().body(Map.of());
        }

        return ResponseEntity.ok(compressionService.upload(file, contentType));
    }
}
