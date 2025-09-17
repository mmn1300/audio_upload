// ====== 전역변수 ======
let recStatus = false;
let timer = null;
let seconds = 0;



// ====== 설정값 ======
const AUDIO_CONSTRAINTS = {
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
};



// ====== 모듈 상태 ======
let mediaRecorder = null;
let chunks = [];
let currentStream = null;



// ====== 유틸 ======
function getSupportedMime() {
    const types = [
        "audio/webm;codecs=opus"
    ];

    return types.find(t => MediaRecorder.isTypeSupported(t))
}


/* 
    녹화 시작/종료시 동작하는 UI 상태 변경 함수

    1. 버튼 상태 변경
    2. 타이머 시작/종료
    3. 업로드 방식 변경 a태그 숨기기/표시
*/
function setRecordingUI(isRecording) {
    if(isRecording){
        $('#recBtn').text("녹음 종료");
        $("#rec-status").removeClass("hidden");
        recStatus = true;

        if (timer === null) {
            // 타이머 시작
            timer = setInterval(() => {
                seconds++;
                $("#rec-time").text(formatTime(seconds));
            }, 1000);
        }

        $("#upload-change").addClass("hidden");
    }else{
        if (timer !== null) {
            // 타이머 정지
            clearInterval(timer);
            timer = null;
            seconds = 0;
        }

        $('#recBtn').text("녹음 시작");
        $("#rec-status").addClass("hidden");
        $("#upload-change").removeClass("hidden");
        recStatus = false;
    }
}


/* 
    초(sec) 정보를 HH:MM:SS 형식으로 변환하는 함수
*/
function formatTime(sec) {
    const hrs = String(Math.floor(sec / 3600)).padStart(2, "0");
    const mins = String(Math.floor((sec % 3600) / 60)).padStart(2, "0");
    const secs = String(sec % 60).padStart(2, "0");
    return `${hrs}:${mins}:${secs}`;
}



// ====== 스트림 & 레코더 준비 ======
/* 
    브라우저 권한 팝업이 뜨며, 승인되면 MediaStream 반환.
*/
async function requestMicStream() {
    return await navigator.mediaDevices.getUserMedia(AUDIO_CONSTRAINTS);
}


/* 
    주어진 stream과 mime으로 new MediaRecorder(...) 생성.
    mime가 비어있으면 브라우저가 알아서 결정
*/
function createMediaRecorder(stream, mime) {
    return mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
}


/* 
    recorder.ondataavailable에서 조각(blob chunk) 수집하여 chunks 배열에 push.
    recorder.onstop에서 최종 처리 로직(Blob 합치기·업로드)을 실행하도록 콜백 연결.
*/
function bindRecorderEvents(recorder, onStop) {
    recorder.ondataavailable = (e) => { if (e.data && e.data.size > 0) chunks.push(e.data); };
    recorder.onstop = onStop;
}



// ====== Blob/File 생성 ======
/* 
    chunks를 하나의 Blob으로 병합.
*/
function buildAudioBlob(mime) {
    return new Blob(chunks, { type: mime || 'audio/webm' });
}


/* 
    Blob을 File 객체로 래핑(업로드 편의를 위함)
*/
function toFile(blob) {
    const name = `record_${Date.now()}.${blob.type.includes('ogg') ? 'ogg' : 'webm'}`;
    return new File([blob], name, { type: blob.type });
}



// ====== 업로드 ======
async function uploadFile(file) {
    // 파일 압축
    const zip = new JSZip();
    zip.file(file.name, file);

    zip.generateAsync({type: 'blob', compression: "DEFLATE", compressionOptions: {level: 6}})
        .then((resZip) => {
            // 업로드
            const fd = new FormData();
            fd.append('file', resZip, file.name + ".zip");
    
            return $.ajax({
                url: '/compression',
                method: 'POST',
                data: fd,
                processData: false,
                contentType: false
            });
        })
}



// ====== 녹음 제어 ======
async function startRecording() {
    try {
        const mime = getSupportedMime();
        chunks = [];

        currentStream = await requestMicStream();
        mediaRecorder = createMediaRecorder(currentStream, mime);
        bindRecorderEvents(mediaRecorder, async () => {
            try {
                const blob = buildAudioBlob(mime);
                const file = toFile(blob);
                await uploadFile(file);
                console.log('압축 파일 업로드 성공:');
            } catch (err) {
                console.error('압축 파일 업로드 실패:', err?.responseText || err);
            } finally {
                cleanupStream();
                setRecordingUI(false);
            }
        });

        mediaRecorder.start(); // 필요 시 mediaRecorder.start(1000) 으로 1초 단위 chunk 수집
        setRecordingUI(true);
    } catch (err) {
        console.error('녹음 시작 실패:', err);
        alert('마이크 권한을 확인해 주세요.');
        setRecordingUI(false);
    }
}


function stopRecording() {
    try {
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
            mediaRecorder.stop();
        } else {
            // 바로 정리 (예외 상황)
            cleanupStream();
            setRecordingUI(false);
        }
    } catch (err) {
        console.error('정지 중 오류:', err);
        cleanupStream();
        setRecordingUI(false);
    }
}


function cleanupStream() {
    if (currentStream) {
        currentStream.getTracks().forEach(t => t.stop());
        currentStream = null;
    }
    mediaRecorder = null;
    chunks = [];
}
