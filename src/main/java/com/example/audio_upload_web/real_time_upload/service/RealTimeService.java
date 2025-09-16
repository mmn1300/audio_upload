package com.example.audio_upload_web.real_time_upload.service;

import com.example.audio_upload_web.audio_upload.constant.UploadPaths;
import com.example.audio_upload_web.exception.AlreadyFinalizedException;
import com.example.audio_upload_web.exception.NoSessionException;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

@Service
public class RealTimeService {

    /*
     * 최종적으로 파일이 업로드 될 위치
     * */
    private final Path uploadRoot = UploadPaths.uploadRoot.getPath();


    /*
     * 청크 파일이 임시로 저장될 위치
     * */
    private final Path tmpRoot = UploadPaths.tmpRoot.getPath();

    /*
     * 업로드 처리 과정을 저장하기 위한 파일
     * */
    private static final String META = "status.txt";

    /*
     * 업로드 처리 과정 3단계
     *
     * 1. UPLOADING : 파일이 업로드 중
     * 2. FINALIZING : 파일 병합 처리과정 중
     * 3. FINALIZED : 파일 병합 완료
     * */
    private static final String UPLOADING  = "UPLOADING";
    private static final String FINALIZING = "FINALIZING";
    private static final String FINALIZED  = "FINALIZED";

    /*
     * 청크 파일을 병합하기 위한 파일
     * */
    private static final String STREAM_FILE = "stream.webm";

    /*
     * 생성된 청크 파일을 자동으로 제거하기 위한 스레드 스케줄러
     * */
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();


    /**
     * 서버 시작시 초기화할 내용.<br/>
     * (파일 경로 생성, 스케줄러 초기화)
     * */
    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadRoot);
        Files.createDirectories(tmpRoot);
        scheduler.setPoolSize(1);
        scheduler.initialize();
    }



    /**
     * 세션 및 파일 UUID값 생성<br/>
     * 청크 파일들을 연속적으로 업로드 받기 위함
     * @return 파일 UUID값
     * @throws IOException 청크 파일 저장 위치 생성 예외
     * */
    public String createSession() throws IOException {
        String uploadId = UUID.randomUUID().toString();
        Path sessionDir = tmpRoot.resolve(uploadId);
        Files.createDirectories(sessionDir.resolve("chunks"));
        Files.writeString(sessionDir.resolve(META), UPLOADING, StandardCharsets.UTF_8);
//        System.out.println("[createSession] " + sessionDir.toAbsolutePath());
        return uploadId;
    }


    /**
     * 청크 파일 저장
     * @param uploadId 업로드 될 파일의 UUID값
     * @param seq 청크 파일의 순서
     * @param part 청크 파일
     * @throws IllegalArgumentException 빈 청크 저장 예외
     * @throws NoSessionException 세션 미존재 예외
     * @throws AlreadyFinalizedException 비 정상 상태 호출 예외
     * @throws IOException 청크 파일 저장 예외
     * */
    public void saveChunk(String uploadId, int seq, MultipartFile part) throws IOException {
        if (part == null || part.isEmpty()) throw new IllegalArgumentException("빈 청크");

        // 경로 지정
        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        if (!Files.exists(statusFile)) throw new NoSessionException();

        // 현재 업로드 상태 확인
        String status = Files.readString(statusFile, StandardCharsets.UTF_8).trim();
        if (!UPLOADING.equals(status)) throw new AlreadyFinalizedException();

        // 업로드 임시 파일 저장 디렉터리 (미존재시)생성
        Files.createDirectories(sessionDir);
        
        // stream 파일 경로 지정
        Path streamFile = sessionDir.resolve(STREAM_FILE);

        // stream 파일에 append 모드로 청크 파일을 그대로 이어붙임
        try (InputStream in = part.getInputStream();
             OutputStream out = Files.newOutputStream(streamFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            in.transferTo(out);
        }
        
//        System.out.println("[appendChunk] uploadId=" + uploadId + " seq=" + seq +
//                " -> " + streamFile.toAbsolutePath());
    }


    /**
     * 청크 파일 병합
     * @param uploadId 업로드 될 파일의 UUID값
     * @param totalChunks 전체 청크 파일 개수
     * @return 파일 병합 처리 상태 {"ok":boolean, "id":String, "key":String, "contentType":String, "size":long}
     * @throws NoSessionException 세션 미존재 예외
     * @throws AlreadyFinalizedException 비 정상 상태 호출 예외
     * @throws IllegalStateException stream 파일 미존재 예외
     * */
    public Map<String, Object> finalize(String uploadId, Integer totalChunks) throws Exception {
        if (totalChunks == null || totalChunks <= 0)
            throw new IllegalArgumentException("totalChunks required");

        // 경로 지정
        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        if (!Files.exists(statusFile)) throw new NoSessionException();

        // 현재 업로드 상태 확인
        String status = Files.readString(statusFile, StandardCharsets.UTF_8).trim();
        if (!UPLOADING.equals(status)) throw new AlreadyFinalizedException();

        // stream 파일 경로 지정
        Path streamFile = sessionDir.resolve(STREAM_FILE);
        if (!Files.exists(streamFile) || Files.size(streamFile) == 0)
            throw new IllegalStateException("NO_STREAM");

        // 파일 크기 변동이 멈출 때까지 짧게 대기(안정화 목적)
        waitFileStable(streamFile, 200, 3000);

        // 상태 전환
        Files.writeString(statusFile, FINALIZING, StandardCharsets.UTF_8);

        // 출력 경로 지정
        String id   = UUID.randomUUID().toString();
        String date = LocalDate.now().toString();
        Path outDir = uploadRoot.resolve(date);
        Files.createDirectories(outDir);

        Path out = outDir.resolve(id + ".webm");

        // ffmpeg 실행 및 로그 수집
        // stream 파일을 읽어 전체 음성 데이터 추출 및 최종 음성 데이터 파일 생성
        String ffLog = runFfmpegCapture(
                sessionDir,
                // PTS(타임스탬프) 재생성
                "-fflags", "+genpts",
                // 입력 파일 (stream.webm)
                "-i", streamFile.toAbsolutePath().toString().replace("\\","/"),
                // 비디오 스트림 무시
                "-vn",
                // 오디오 코덱: libopus (WebM용 표준)
                "-c:a", "libopus",
                // 오디오 비트레이트 64kbps
                "-b:a", "64k",
                // 샘플레이트 48kHz
                "-ar", "48000",
                // 채널 수: 1 (mono)
                "-ac", "1",
                // 출력 파일 경로
                out.toAbsolutePath().toString().replace("\\","/")
        );

        // 로그 작성
        Files.writeString(sessionDir.resolve("ffmpeg_final.log"), ffLog, StandardCharsets.UTF_8);

        long size = Files.size(out);
        String contentType = Files.probeContentType(out);

        // 상태 전환
        Files.writeString(statusFile, FINALIZED, StandardCharsets.UTF_8);
        cleanupLater(sessionDir, Duration.ofSeconds(60));

        return Map.of(
                "ok", true,
                "id", id,
                "key", date + "/" + out.getFileName().toString(),
                "contentType", contentType,
                "size", size
        );
    }



    /**
     * ffmpeg 프로세스 실행 및 로그 수집
     * @param workDir 실행 대상 파일들이 존재하는 디렉터리 경로
     * @param args ffmpeg 프로세스 실행 옵션들
     * @return 수집한 로그
     * @throws RuntimeException ffmpeg 프로세스 실행 예외
     * */
    private String runFfmpegCapture(Path workDir, String... args) throws Exception {
        // 명령어 셋팅
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        Collections.addAll(cmd, args);

        // ffmpeg 프로세스 실행
        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();

        // 로그 수집
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }

        // 프로세스 종료 대기
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("FFmpeg 실패 code=" + code + "\n" + sb);
        }

        // 수집한 로그 반환
        return sb.toString();
    }


    /**
     * src/main/resources/uploads/tmp/ 경로 내부 청크 파일 자동 삭제
     * @param sessionDir 파일이 저장된 위치
     * @param delay 파일을 남겨둘 시간
     * */
    private void cleanupLater(Path sessionDir, Duration delay) {
        scheduler.schedule(() -> {
            try (Stream<Path> s = Files.walk(sessionDir)) {
                s.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
            } catch (IOException ignore) {}
        }, new Date(System.currentTimeMillis() + delay.toMillis()));
    }


    /**
     * 파일이 정상적으로 전부 작성 될 때까지 일정시간 대기<br/>
     * (정상 처리시간 확보 목적)
     * @param f stream 파일 경로
     * @param stableMillis 안정화 기준 시간
     * @param timeoutMs 최대 대기 시간
     * @return 정상 처리 여부
     * */
    private boolean waitFileStable(Path f, long stableMillis, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastSize = -1, lastChangeTs = System.currentTimeMillis();
        while (true) {
            if (!Files.exists(f)) {
                if (System.currentTimeMillis() > deadline) return false;
                Thread.sleep(50);
                continue;
            }
            long sz = Files.size(f);
            long now = System.currentTimeMillis();
            if (sz != lastSize) { lastSize = sz; lastChangeTs = now; }
            if (sz > 0 && (now - lastChangeTs) >= stableMillis) return true;
            if (now >= deadline) return false;
            Thread.sleep(50);
        }
    }

}
