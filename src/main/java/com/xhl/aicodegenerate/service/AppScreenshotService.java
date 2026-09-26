package com.xhl.aicodegenerate.service;

/**
 * 应用封面截图服务
 */
public interface AppScreenshotService {

    /**
     * 异步截取应用网页封面并更新 app.cover
     *
     * @param appId     应用 ID
     * @param targetUrl 待截取的页面访问 URL 或本地文件/目录路径
     */
    void captureAppCoverAsync(Long appId, String targetUrl);

    /**
     * 同步截取应用封面（供测试或即时调用）
     *
     * @param appId     应用 ID
     * @param targetUrl 待截取页面 URL 或本地路径
     * @return 封面图相对访问路径 (例如 /api/static/cover/app_1.png)
     */
    String captureAppCover(Long appId, String targetUrl);
}
