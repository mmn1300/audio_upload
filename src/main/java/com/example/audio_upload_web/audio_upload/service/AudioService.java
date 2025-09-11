package com.example.audio_upload_web.audio_upload.service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

@Service
public class AudioService {

    private final Path uploadRoot = Paths.get("D:\\audio_upload\\uploads\\audio");
    private final Path tmpRoot = Paths.get(
            System.getProperty("user.dir")
            + File.separator
            +"src\\main\\resources\\uploads\\tmp"
    );

    private static final String META = "status.txt";               // 세션 상태 파일
    private static final String UPLOADING  = "UPLOADING";
    private static final String FINALIZING = "FINALIZING";
    private static final String FINALIZED  = "FINALIZED";

    private static final int    CHUNK_POLL_INTERVAL_MS = 100;   // finalize 대기 폴링 간격
    private static final int    CHUNK_POLL_TIMEOUT_MS  = 3000;  // finalize 대기 타임아웃(필요시 상향)
    private static final String CHUNK_PATTERN          = "chunk_%06d.webm";
    private static final String INPUT_EXT              = ".webm"; // MediaRecorder 청크 확장자

    private static final String STREAM_FILE = "stream.webm";

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadRoot);
        Files.createDirectories(tmpRoot);
        scheduler.setPoolSize(1);
        scheduler.initialize();
    }

    public String guessExt(String ct) {
        return switch (ct) {
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg" -> "mp3";
            default -> null;
        };
    }


    public Map<String, Object> upload(MultipartFile file, String contentType) throws IOException {
        // 저장 (UUID 파일명)
        String ext = guessExt(contentType); // 간단 추정: webm/ogg/wav/mp3
        String fileName = UUID.randomUUID() + (ext != null ? "." + ext : ".mp3");
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




    public String createSession() throws IOException {
        String uploadId = UUID.randomUUID().toString();
        Path sessionDir = tmpRoot.resolve(uploadId);
        Files.createDirectories(sessionDir.resolve("chunks"));
        Files.writeString(sessionDir.resolve(META), UPLOADING, StandardCharsets.UTF_8);
        System.out.println("[createSession] " + sessionDir.toAbsolutePath());
        return uploadId;
    }


    public void saveChunk(String uploadId, int seq, MultipartFile part) throws IOException {
        if (part == null || part.isEmpty()) throw new IllegalArgumentException("빈 청크");

        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        if (!Files.exists(statusFile)) throw new NoSessionException();

        String status = Files.readString(statusFile, StandardCharsets.UTF_8).trim();
        if (!UPLOADING.equals(status)) throw new AlreadyFinalizedException();

        Files.createDirectories(sessionDir);
        Path streamFile = sessionDir.resolve(STREAM_FILE);

        // append 모드로 그대로 이어붙인다 (헤더는 첫 조각에만 존재)
        try (InputStream in = part.getInputStream();
             OutputStream out = Files.newOutputStream(streamFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            in.transferTo(out);
        }

        // (선택) 마지막 수신 seq를 기록해 추후 totalChunks 검증에 활용
        Files.writeString(sessionDir.resolve("last_seq.txt"),
                Integer.toString(seq), StandardCharsets.UTF_8);
        System.out.println("[appendChunk] uploadId=" + uploadId + " seq=" + seq +
                " -> " + streamFile.toAbsolutePath());
    }


    private boolean waitForAllChunks(Path chunksDir, int total, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean ok = true;
            for (int i = 1; i <= total; i++) {
                Path f = chunksDir.resolve(String.format(CHUNK_PATTERN, i));
                if (!Files.exists(f) || Files.size(f) == 0) { ok = false; break; }
            }
            if (ok) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(CHUNK_POLL_INTERVAL_MS);
        }
    }


    private boolean waitForAllChunksStable(Path chunksDir, int total, long stableMillis, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            boolean ok = true;
            for (int i = 1; i <= total; i++) {
                Path f = chunksDir.resolve(String.format(CHUNK_PATTERN, i));
                if (!waitFileStable(f, stableMillis, Math.min(800, timeoutMs))) { ok = false; break; }
            }
            if (ok) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(100);
        }
    }


    public Map<String, Object> finalize(String uploadId, Integer totalChunks) throws Exception {
        if (totalChunks == null || totalChunks <= 0)
            throw new IllegalArgumentException("totalChunks required");

        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        if (!Files.exists(statusFile)) throw new NoSessionException();

        String status = Files.readString(statusFile, StandardCharsets.UTF_8).trim();
        if (!UPLOADING.equals(status)) throw new AlreadyFinalizedException();

        Path streamFile = sessionDir.resolve(STREAM_FILE);
        if (!Files.exists(streamFile) || Files.size(streamFile) == 0)
            throw new IllegalStateException("NO_STREAM");

        // (선택) 안정화: 파일 크기 변동이 멈출 때까지 짧게 대기
        waitFileStable(streamFile, /*stableMillis*/200, /*timeoutMs*/3000);

        // 상태 전환
        Files.writeString(statusFile, FINALIZING, StandardCharsets.UTF_8);

        // 출력 경로
        String id   = UUID.randomUUID().toString();
        String date = LocalDate.now().toString();
        Path outDir = uploadRoot.resolve(date);
        Files.createDirectories(outDir);

        // 필요 시 WAV로도 가능 (주석 참고)
        Path out = outDir.resolve(id + ".webm");

        // ffmpeg 실행: 단일 입력 stream.webm → 재인코딩(안정)
        // (참고) -c copy가 먹히는 환경도 있으나, 타임스탬프/컨테이너 꼬임 방지를 위해 재인코딩 권장
        String ffLog = runFfmpegCapture(
                sessionDir,
                "-fflags", "+genpts",
                "-i", streamFile.toAbsolutePath().toString().replace("\\","/"),
                "-vn",
                "-c:a", "libopus", "-b:a", "64k", "-ar", "48000", "-ac", "1",
                out.toAbsolutePath().toString().replace("\\","/")
        );
        Files.writeString(sessionDir.resolve("ffmpeg_final.log"), ffLog, StandardCharsets.UTF_8);

        long size = Files.size(out);
        String contentType = Files.probeContentType(out);

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


    private String runFfmpegCapture(Path workDir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        Collections.addAll(cmd, args);

        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())   // 작업 디렉토리: 세션 디렉토리
                .redirectErrorStream(true)
                .start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("FFmpeg 실패 code=" + code + "\n" + sb);
        }
        return sb.toString();
    }


    private void cleanupLater(Path sessionDir, Duration delay) {
        scheduler.schedule(() -> {
            try (Stream<Path> s = Files.walk(sessionDir)) {
                s.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
            } catch (IOException ignore) {}
        }, new Date(System.currentTimeMillis() + delay.toMillis()));
    }


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



    public static class NoSessionException extends RuntimeException {
        public NoSessionException() { super("NO_SESSION"); }
    }
    public static class AlreadyFinalizedException extends RuntimeException {
        public AlreadyFinalizedException() { super("FINALIZED"); }
    }
}
