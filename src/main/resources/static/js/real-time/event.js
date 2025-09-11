$(document).ready(() => {
    $('#recBtn').on('click', (e) => {
        if(recStatus){
            stopRecording();
        }else{
            startRecording();
        }
    });
});