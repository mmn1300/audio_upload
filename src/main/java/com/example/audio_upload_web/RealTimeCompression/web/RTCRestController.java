package com.example.audio_upload_web.RealTimeCompression.web;

import com.example.audio_upload_web.RealTimeCompression.service.RTCService;
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
@RequestMapping("/rtc")
public class RTCRestController {

    @Autowired
    private RTCService RTCService;


    /**
     * 세션 생성
     * @return {"ok":boolean, "uploadId":String}
     * @throws IOException 청크 파일 저장 위치 생성 예외
     * */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> createSession() throws IOException {
        String uploadId = RTCService.createSession();
        return ResponseEntity.ok(Map.of("ok", true, "uploadId", uploadId));
    }


    /**
     * 청크 파일을 업로드 받는 컨트롤러
     * @param uploadId 업로드 될 파일의 UUID값
     * @param seq 청크 파일 업로드 순번
     * @param file 압축된 청크 파일
     * @return {"ok":boolean}
     * @throws IOException 청크 파일 저장 예외
     * */
    @PostMapping(value="/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int seq,
            @RequestParam("file") MultipartFile file) throws IOException {

        // 검증
        if (!file.getContentType().endsWith("/zip")) {
            return ResponseEntity.badRequest().body(Map.of());
        }

        // 청크 파일 저장
        RTCService.saveChunk(uploadId, seq, file);
        return ResponseEntity.ok(Map.of("ok", true));
    }



    /**
     * 모든 청크 파일 업로드 완료 신호를 받는 컨트롤러
     * @param uploadId 업로드 될 파일의 UUID값
     * @param totalChunks 업로드된 모든 청크 파일 수
     * @return {"ok":boolean, "id":String, "key":String "contentType":String, "size":long}
     * */
    @PostMapping("/finalize")
    public ResponseEntity<Map<String, Object>> finalizeUpload(
            @RequestParam String uploadId,
            @RequestParam Integer totalChunks) throws Exception {
        return ResponseEntity.ok(RTCService.finalize(uploadId, totalChunks));
    }
}
