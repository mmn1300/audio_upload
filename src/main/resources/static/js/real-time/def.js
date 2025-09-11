// ====== 전역변수 ======
let recStatus = false;
let timer = null;
let seconds = 0;


// ====== 유틸 ======
const API = {
    session:  '/audio/session',
    chunk:    '/audio/chunk',
    finalize: '/audio/finalize'
};

let mediaRecorder, stream;
let uploadId = null;
let seq = 0;
let sending = Promise.resolve(); // 업로드 순서 보장(백프레셔)
let stoppedP = null;


function formatTime(sec) {
    const hrs = String(Math.floor(sec / 3600)).padStart(2, "0");
    const mins = String(Math.floor((sec % 3600) / 60)).padStart(2, "0");
    const secs = String(sec % 60).padStart(2, "0");
    return `${hrs}:${mins}:${secs}`;
}


// ====== 스트림 & 레코더 준비 ======
function getMime() {
    const cands = ['audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg'];
    for (const m of cands) if (MediaRecorder.isTypeSupported(m)) return m;
    return '';
}

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

async function createSession() {
    const res = await $.post(API.session);
    if (!res.ok) throw new Error('세션 생성 실패');
    return res.uploadId;
}

async function sendChunk(id, index, blob) {
    const fd = new FormData();
    fd.append('uploadId', id);
    fd.append('seq', String(index));
    fd.append('file', new File([blob], `chunk_${index}.webm`, { type: blob.type || 'audio/webm' }));
    return $.ajax({
        url: API.chunk,
        method: 'POST',
        data: fd,
        processData: false,
        contentType: false
    });
}

async function finalizeUpload(id, total) {
    return $.ajax({
        url: API.finalize,
        method: 'POST',
        data: { uploadId: id, totalChunks: total }
    });
}

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

async function startRecording() {
    try {
        uploadId = await createSession();
        seq = 0;

        stream = await navigator.mediaDevices.getUserMedia({
            audio: { echoCancellation:true, noiseSuppression:true, autoGainControl:true }
        });

        const mime = getMime();
        mediaRecorder = mime ? new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 128000 })
                                : new MediaRecorder(stream, { audioBitsPerSecond: 128000 });

        bindRecorder(mediaRecorder);
        mediaRecorder.start(1000); // 1초 단위 청크
        setUI(true);
    } catch (e) {
        console.error(e);
        alert('마이크 권한 또는 세션 생성 실패');
        setUI(false);
    }
}

async function stopRecording() {
    try {
        // 1) 남은 버퍼 강제 방출
        if (mediaRecorder && mediaRecorder.state !== 'inactive') {
            mediaRecorder.requestData();   // 마지막 버퍼 즉시 배출
            mediaRecorder.stop();
        }
        if (stream) stream.getTracks().forEach(t => t.stop());

        // 2) 'stop' 이벤트까지 대기(마지막 dataavailable 발생 보장)
        await stoppedP;

        // 3) 지금까지 체인에 올라간 모든 청크 업로드 완료 대기
        await sending;

        // 4) 이제서야 finalize
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