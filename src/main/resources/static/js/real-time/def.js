// ====== 전역변수 ======
let recStatus = false;
let timer = null;
let seconds = 0;
const sendSec = 3000; // 3초 단위 청크 파일 전송


// ====== 유틸 ======
const API = {
    session:  '/real-time/session',
    chunk:    '/real-time/chunk',
    finalize: '/real-time/finalize'
};

let mediaRecorder, stream;
let uploadId = null;
let seq = 0;
let sending = Promise.resolve(); // 업로드 순서 보장(백프레셔)
let stoppedP = null;


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
function getMime() {
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
function setUI(rec) {
    if(rec){
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
        recStatus = false;
        $("#upload-change").removeClass("hidden");
    }
}


/* 
    세션을 생성하고 업로드를 위한 밑준비를 요청하는 함수
*/
async function createSession() {
    const res = await $.post(API.session);
    if (!res.ok) throw new Error('세션 생성 실패');
    return res.uploadId;
}


/* 
    청크 파일을 업로드하는 함수
*/
async function sendChunk(id, index, blob) {
    const fd = new FormData();
    fd.append('uploadId', id);
    fd.append('seq', String(index));
    const file = new File([blob], `chunk_${index}.webm`, { type: blob.type || 'audio/webm' });
    // console.log(file.size);
    fd.append('file', file);
    return $.ajax({
        url: API.chunk,
        method: 'POST',
        data: fd,
        processData: false,
        contentType: false
    });
}


/* 
    마지막 청크 파일 업로드 이후 업로드 종료를 서버에 알리는 함수
*/
async function finalizeUpload(id, total) {
    return $.ajax({
        url: API.finalize,
        method: 'POST',
        data: { uploadId: id, totalChunks: total }
    });
}


/* 
    녹음 종료 이벤트
*/
function bindRecorder(recorder) {
    stoppedP = new Promise(resolve => {
        recorder.addEventListener('stop', resolve, { once: true });
    });

    recorder.ondataavailable = (e) => {
    if (!e.data || e.data.size === 0) return;
        const mySeq = ++seq;
        sending = sending.then(() => sendChunk(uploadId, mySeq, e.data))
                            .catch(err => console.error('chunk 업로드 실패:', err));
    };
}


/* 
    브라우저 권한 팝업이 뜨며, 승인되면 MediaStream 반환.
*/
async function requestMicStream() {
    return await navigator.mediaDevices.getUserMedia({
            audio: { echoCancellation:true, noiseSuppression:true, autoGainControl:true }
        });
}


// ====== 녹음 제어 ======
async function startRecording() {
    try {
        uploadId = await createSession();
        seq = 0;

        stream = await requestMicStream();

        const mime = getMime();
        mediaRecorder = mime ? new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 128000 })
                                : new MediaRecorder(stream, { audioBitsPerSecond: 128000 });

        bindRecorder(mediaRecorder);
        mediaRecorder.start(sendSec);
        setUI(true);
    } catch (e) {
        console.error(e);
        alert('마이크 권한 또는 세션 생성 실패');
        setUI(false);
    }
}

async function stopRecording() {
    try {
        // 남은 버퍼 강제 방출
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
            mediaRecorder.requestData();   // 마지막 버퍼 즉시 배출
            mediaRecorder.stop();
        }
        if (stream) stream.getTracks().forEach(t => t.stop());

        // stop 이벤트까지 대기(마지막 dataavailable 발생 보장)
        await stoppedP;

        // 지금까지 체인에 올라간 모든 청크 업로드 완료 대기
        await sending;

        // finalize
        const res = await finalizeUpload(uploadId, seq);
        if (res.ok) {
            console.log('최종 파일 준비 완료:', res);
        } else {
            console.error(res);
        }
    } catch (e) {
        console.error('finalize 실패:', e);
    } finally {
        setUI(false);
        uploadId = null; seq = 0; mediaRecorder = null; stream = null;
    }
}