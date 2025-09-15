package com.example.audio_upload_web.exception;

public class AlreadyFinalizedException extends RuntimeException {
    public AlreadyFinalizedException() { super("FINALIZED"); }
}
