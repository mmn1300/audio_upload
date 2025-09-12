package com.example.audio_upload_web.audio_upload.web;

import com.example.audio_upload_web.audio_upload.service.AudioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.util.Map;

@RestController
@RequestMapping("/audio")
public class AudioRestController {

    @Autowired
    private AudioService audioService;


    @PostMapping(consumes= MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAudio(@RequestParam("file") MultipartFile file) throws IOException {
        // 검증
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "빈 파일"));
        String contentType = file.getContentType(); // 예: audio/webm
        if (contentType == null || !(contentType.startsWith("audio/") || contentType.equals("application/octet-stream"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "오디오 파일만 허용"));
        }

        return ResponseEntity.ok(audioService.upload(file, contentType));
    }
}
