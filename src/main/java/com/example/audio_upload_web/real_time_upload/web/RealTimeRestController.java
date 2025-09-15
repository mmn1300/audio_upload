package com.example.audio_upload_web.real_time_upload.web;

import com.example.audio_upload_web.real_time_upload.service.RealTimeService;
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
@RequestMapping("/real-time")
public class RealTimeRestController {

    @Autowired
    private RealTimeService realTimeService;


    /**
    * 세션 생성
    * @throws IOException 청크 파일 저장 위치 생성 예외
    * */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> createSession() throws IOException {
        String uploadId = realTimeService.createSession();
        return ResponseEntity.ok(Map.of("ok", true, "uploadId", uploadId));
    }


    /**
     * 청크 파일을 업로드 받는 컨트롤러
     * @param uploadId 업로드 될 파일의 UUID값
     * @param seq 청크 파일 업로드 순번
     * @param file 청크 파일
     * @throws IOException 청크 파일 저장 예외
     * */
    @PostMapping(value="/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int seq,
            @RequestParam("file") MultipartFile file) throws IOException {

        realTimeService.saveChunk(uploadId, seq, file);
        return ResponseEntity.ok(Map.of("ok", true));
    }


    /**
     * 모든 청크 파일 업로드 완료 신호를 받는 컨트롤러
     * @param uploadId 업로드 될 파일의 UUID값
     * @param totalChunks 업로드된 모든 청크 파일 수
     * */
    @PostMapping("/finalize")
    public ResponseEntity<Map<String, Object>> finalizeUpload(
            @RequestParam String uploadId,
            @RequestParam Integer totalChunks) throws Exception {
        Map<String,Object> res = realTimeService.finalize(uploadId, totalChunks);
        return ResponseEntity.ok(res);
    }
}
