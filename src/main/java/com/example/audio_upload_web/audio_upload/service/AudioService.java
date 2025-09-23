package com.example.audio_upload_web.audio_upload.service;

import com.example.audio_upload_web.constant.UploadPaths;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.nio.file.StandardCopyOption;

@Service
public class AudioService {

    /*
    * 최종적으로 파일이 업로드 될 위치 (두 업로드 방식 공통)
    * */
    private final Path uploadRoot = UploadPaths.uploadRoot.getPath();
    
    

    /**
     * 파일을 업로드 함 (일괄 업로드)
     * @param file 업로드된 파일
     * @param contentType 파일의 형태
     * @throws IOException 파일 저장 예외
     * */
    public Map<String, Object> upload(MultipartFile file, String contentType) throws IOException {
        // 저장 (UUID 파일명)
        String ext = guessExt(contentType); // 간단 추정: webm/ogg/wav/mp3
        String fileName = UUID.randomUUID() + (ext != null ? "." + ext : ".webm");
        Path dateDir = uploadRoot.resolve(LocalDate.now().toString());
        Files.createDirectories(dateDir);
        Path dest = dateDir.resolve(fileName);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

        // 응답
        return Map.of(
            "ok", true,
            "storedPath", dest.toString(),
            "contentType", contentType,
            "size", file.getSize()
        );
    }


    /**
     * 파일 형식 추정
     * @param ct 파일 형태
     * */
    private String guessExt(String ct) {
        return switch (ct) {
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg" -> "mp3";
            default -> null;
        };
    }

}
