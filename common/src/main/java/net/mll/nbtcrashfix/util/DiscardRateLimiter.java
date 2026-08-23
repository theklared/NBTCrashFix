package net.mll.nbtcrashfix.util;

public final class DiscardRateLimiter {
    public int totalDiscardCount = 0;
    public boolean rateLimited = false;
    public long lastDiscardTime = 0L;
    public long windowStart = 0L;
    public int discardCount = 0;
    public long lastLogTime = 0L;
    public long suppressedSinceLastLog = 0L;
}