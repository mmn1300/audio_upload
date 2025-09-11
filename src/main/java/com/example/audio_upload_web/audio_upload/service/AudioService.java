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
        return uploadId;
    }

    public void saveChunk(String uploadId, int seq, MultipartFile part) throws IOException {
        if (part.isEmpty()) throw new IllegalArgumentException("빈 청크");
        // (선택) contentType 검사
        if (part == null || part.isEmpty()) throw new IllegalArgumentException("빈 청크");

        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        Path chunksDir  = sessionDir.resolve("chunks");
        String name = String.format("chunk_%06d.webm", seq); // 0패딩으로 정렬
        Path dest = sessionDir.resolve(name).normalize();

        if (!Files.isDirectory(sessionDir)) throw new IllegalStateException("세션 없음");
        if (!dest.toAbsolutePath().startsWith(tmpRoot.toAbsolutePath())) throw new SecurityException("경로 위변조");

        try (var in = part.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Map<String, Object> finalize(String uploadId, Integer totalChunks) throws Exception {
        Path sessionDir = tmpRoot.resolve(uploadId);
        Path statusFile = sessionDir.resolve(META);
        Path chunksDir  = sessionDir.resolve("chunks");

        if (!Files.isDirectory(chunksDir)) {
            throw new IllegalStateException("세션 없음");
        }

        // concat list 파일 생성
        Path listFile = sessionDir.resolve("list.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(listFile, StandardCharsets.UTF_8)) {
            writer.write("ffconcat version 1.0\n");
            try (Stream<Path> s = Files.list(chunksDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".webm"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String full = p.toAbsolutePath().toString();
                            // 1) 백슬래시 이스케이프
                            full = full.replace("\\", "\\\\");
                            // 2) 작은따옴표 이스케이프
                            full = full.replace("'", "'\\''");
                            writer.write("file '" + full + "'\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            }
        }

        // 최종 출력 경로
        String id = UUID.randomUUID().toString();
        String date = LocalDate.now().toString();
        Path outDir = uploadRoot.resolve(date);
        Files.createDirectories(outDir);

        // 안정적인 병합 (재인코딩)
        Path out = outDir.resolve(id + ".webm");
        runFfmpegCapture(
            "-f", "concat", "-safe", "0", "-i", listFile.toString(),
            "-vn",
            "-c:a", "libopus", "-b:a", "64k", "-ar", "48000", "-ac", "1",
            out.toString()
        );

        long size = Files.size(out);
        String contentType = Files.probeContentType(out);

        // FINALIZED 마킹
        Files.writeString(statusFile, FINALIZED, StandardCharsets.UTF_8);

        // 정리는 조금 뒤에(지연 삭제) - finalize 이후 늦게 오는 청크가 있어도 세션은 이미 FINALIZED라 거부됨
        cleanupLater(sessionDir, Duration.ofSeconds(60));

        // Map 으로 반환
        return Map.of(
                "ok", true,
                "id", id,
                "key", date + "/" + out.getFileName().toString(),
                "contentType", contentType,
                "size", size
        );
    }

    private String runFfmpegCapture(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("FFmpeg 실패 code=" + code + "\n" + sb);
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
}
