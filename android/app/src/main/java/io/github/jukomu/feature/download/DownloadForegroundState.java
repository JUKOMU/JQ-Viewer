package io.github.jukomu.feature.download;

import java.util.HashSet;
import java.util.Set;

/**
 * 对下载前台成员信息和版本发布进行序列化处理。
 */
final class DownloadForegroundState {

    interface Publisher {
        void publish(Snapshot snapshot);
    }

    private final Set<String> activeTaskIds = new HashSet<>();
    private int revision;

    synchronized void start(String taskId, Publisher publisher) {
        if (activeTaskIds.add(taskId)) {
            publisher.publish(snapshot());
        }
    }

    synchronized void stop(String taskId, Publisher publisher) {
        if (activeTaskIds.remove(taskId)) {
            publisher.publish(snapshot());
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(++revision, activeTaskIds.size());
    }

    static final class Snapshot {
        final int revision;
        final int activeCount;

        Snapshot(int revision, int activeCount) {
            this.revision = revision;
            this.activeCount = activeCount;
        }
    }
}
