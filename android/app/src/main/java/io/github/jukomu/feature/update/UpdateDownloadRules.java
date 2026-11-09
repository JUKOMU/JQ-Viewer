package io.github.jukomu.feature.update;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * 更新下载的纯超时和失败判定。不依赖 Android，便于在没有设备的环境里测试。
 */
final class UpdateDownloadRules {
    private UpdateDownloadRules() {
    }

    /**
     * 绝对截止优先于空闲读超时。其它 IO 错误原样返回。
     * 截止和空闲都保留原始异常作为 cause，界面文案使用这里的消息。
     */
    static IOException readFailure(boolean expired, IOException error, String absoluteMessage, String idleMessage) {
        if (expired) {
            return new IOException(absoluteMessage, error);
        }
        if (error instanceof SocketTimeoutException) {
            return new IOException(idleMessage, error);
        }
        return error;
    }

    /**
     * 选中的下载源没有完整结束时，返回原来的网络或 HTTP 错误。两端都失败时写明各自原因。
     * 完整结束时返回 null，由调用方继续校验安装包。
     */
    static String failureIfDownloadIncomplete(UpdateRaceState.Source winner, boolean winnerCompleted,
                                              String githubError, String giteeError) {
        if (winner == null) {
            return "GitHub 与 Gitee 更新包均下载失败。GitHub: "
                + sourceFailureText(githubError) + "；Gitee: " + sourceFailureText(giteeError);
        }
        if (!winnerCompleted) {
            return winner == UpdateRaceState.Source.GITHUB
                ? sourceFailureText(githubError) : sourceFailureText(giteeError);
        }
        return null;
    }

    private static String sourceFailureText(String error) {
        return error == null || error.isEmpty() ? "未知错误" : error;
    }
}
