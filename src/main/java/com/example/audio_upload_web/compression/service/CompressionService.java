package com.example.audio_upload_web.compression.service;

import com.example.audio_upload_web.constant.UploadPaths;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Log4j2
public class CompressionService {

    /*
     * 최종적으로 파일이 업로드 될 위치 (두 업로드 방식 공통)
     * */
    private final Path uploadRoot = UploadPaths.uploadRoot.getPath();



    /**
     * 파일을 업로드 함 (일괄 업로드)
     * @param file 업로드된 파일
     * @param contentType 파일의 형태
     * @return 파일 저장 응답 {"ok":boolean, "storedPath":String, "contentType":String, "size":long}
     * @throws IOException 파일 저장 예외
     * */
    public Map<String, Object> upload(MultipartFile file, String contentType) throws IOException {
        // 압축 해제 및 저장
        String uploadPath = unzipFile(file);

        // 응답
        if (!uploadPath.isEmpty()) {
            return Map.of(
                    "ok", true,
                    "storedPath", uploadPath,
                    "contentType", contentType,
                    "size", file.getSize()
            );
        }else{
            return Map.of(
                    "ok", false,
                    "storedPath", "",
                    "contentType", contentType,
                    "size", 0L
            );
        }
    }


    /**
     * 압축 파일 압축 해제
     * @param file 압축되어있는 파일
     * @return 파일 저장 경로
     * @throws IOException 파일 저장 예외
     * */
    private String unzipFile(MultipartFile file) throws IOException {
        Path dateDir = uploadRoot.resolve(LocalDate.now().toString());
        Files.createDirectories(dateDir);

        String fileNameUUID = UUID.randomUUID() + ".webm";
        Path uploadPath = dateDir.resolve(fileNameUUID).normalize();

        // zip 파일 압축 해제
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())){
            ZipEntry entry;

            // entry를 하나씩 읽어옴
            while((entry = zis.getNextEntry()) != null){

                if(!uploadPath.startsWith(dateDir.normalize())){
                    zis.closeEntry();
                    throw new IOException("잘못된 zip entry입니다. " + entry.getName());
                }
                
                if(entry.isDirectory()){
                    // entry가 디렉터리일 경우 해당 디렉터리 생성
                    Files.createDirectories(uploadPath);
                }else{
                    // entry가 파일일 경우 해당 파일이 위치할 디렉터리 생성
                    Files.createDirectories(uploadPath.getParent());
                    
                    // 파일 저장
                    try (OutputStream os = Files.newOutputStream(uploadPath)){
                        byte[] buffer = new byte[1024];
                        int len;
                        while((len = zis.read(buffer)) > 0){
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }

            // entry 종료
            zis.closeEntry();
            
            // 파일 저장 경로 반환
            return uploadPath.toString();
        }catch (IOException e){
            log.error("압축 파일 저장 오류 : " + e.getMessage());
            return "";
        }
    }
}
