package ru.koren.max.application;

/** Turns one stored MAX update into application commands. Throwing leaves the event in the inbox. */
public interface UpdateHandler {
    void handle(String payload);
}
