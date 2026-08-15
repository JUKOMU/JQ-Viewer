package io.github.jukomu.feature.update;

/**
 * 双源 APK 下载的原子竞速状态。
 */
public final class UpdateRaceState {

    public static final long RACE_THRESHOLD_BYTES = 10L * 1024L * 1024L;

    private Source winner;
    private int unfinishedSources = 2;
    private boolean cancelled;

    /**
     * 当源达到阈值时原子选择胜者。
     */
    public synchronized boolean trySelectWinner(Source source, long downloadedBytes) {
        if (cancelled || winner != null || downloadedBytes < RACE_THRESHOLD_BYTES) {
            return winner == source;
        }
        winner = source;
        return true;
    }

    /**
     * 标记一个下载源结束，并返回是否已经没有可用竞争者。
     */
    public synchronized boolean sourceFinished(boolean successful) {
        return sourceFinished(null, successful);
    }

    /**
     * 标记指定下载源结束。若 APK 小于竞速阈值，首个完整下载源作为胜者。
     */
    public synchronized boolean sourceFinished(Source source, boolean successful) {
        if (unfinishedSources > 0) {
            unfinishedSources--;
        }
        if (winner == null && successful && source != null && !cancelled) {
            winner = source;
        }
        return winner == null && unfinishedSources == 0;
    }

    public synchronized void cancel() {
        cancelled = true;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized Source getWinner() {
        return winner;
    }

    public synchronized int getUnfinishedSources() {
        return unfinishedSources;
    }

    public enum Source {
        GITHUB,
        GITEE
    }
}
