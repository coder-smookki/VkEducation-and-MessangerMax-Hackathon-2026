package ru.koren.max.application;

/**
 * Stands in until the bot dialog exists. It refuses instead of pretending: a refused update keeps
 * its row with processed_at IS NULL, so nothing is silently swallowed after MAX was acknowledged.
 */
public class NoRouteUpdateHandler implements UpdateHandler {

    @Override
    public void handle(String payload) {
        throw new UnsupportedOperationException("No bot dialog is wired yet");
    }
}
