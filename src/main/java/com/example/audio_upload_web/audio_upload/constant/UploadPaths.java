package com.example.audio_upload_web.audio_upload.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
@AllArgsConstructor
public enum UploadPaths {

    /*
     * 최종적으로 파일이 업로드 될 위치
     * */
    uploadRoot(Paths.get("D:\\audio_upload\\uploads\\audio")),
//     uploadRoot(Paths.get("/var/lib/audio_upload")),


    /*
     * 청크 파일이 임시로 저장될 위치
     * */
    tmpRoot(Paths.get(
            System.getProperty("user.dir")
                    + File.separator
                    + "src\\main\\resources\\uploads\\tmp"
//                    + "src/main/resources/uploads/tmp"
    )),


    /*
    * 업로드 시작/종료 시간 측정 결과 파일이 저장될 위치 (테스트용)
    * */
    timeFile(Paths.get(
            System.getProperty("user.dir")
                    + File.separator
                    + "upload_log.txt"));



    private final Path path;

}
