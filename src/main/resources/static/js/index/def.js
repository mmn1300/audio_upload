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
    const candidates = [
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/ogg'
    ];
    for (const m of candidates) {
        if (window.MediaRecorder && MediaRecorder.isTypeSupported(m)) return m;
    }
    return ''; // 브라우저가 정하도록 둠
}

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
    }else{
        if (timer !== null) {
            // 타이머 정지
            clearInterval(timer);
            timer = null;
            seconds = 0;
        }

        $('#recBtn').text("녹음 시작");
        $("#rec-status").addClass("hidden");
        recStatus = false;
    }
}

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
function uploadFile(file) {
    const fd = new FormData();
    fd.append('file', file);

    return $.ajax({
        url: '/audio',
        method: 'POST',
        data: fd,
        processData: false,
        contentType: false
    });
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
                const res = await uploadFile(file);
                console.log('업로드 성공:', res);
            } catch (err) {
                console.error('업로드 실패:', err?.responseText || err);
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
