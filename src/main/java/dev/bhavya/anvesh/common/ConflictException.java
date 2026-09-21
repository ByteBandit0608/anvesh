package dev.bhavya.anvesh.common;

/** Maps to HTTP 409: the request is valid but the resource's current state forbids it. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
