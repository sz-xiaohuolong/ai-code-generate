package com.xhl.aicodegenerate.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.model.dto.app.AppQueryRequest;
import com.xhl.aicodegenerate.model.vo.AppVO;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://github.com/sz-xiaohuolong">不会喷火的小火龙</a>
 */
public interface AppService extends IService<App> {

    /**
     * 校验应用。
     *
     * @param app 应用
     * @param add 是否为创建校验
     */
    void validApp(App app, boolean add);

    /**
     * 获取查询条件。
     *
     * @param appQueryRequest 应用查询请求
     * @return 查询条件
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 获取应用脱敏信息。
     *
     * @param app 应用
     * @return 应用脱敏信息
     */
    AppVO getAppVO(App app);

    /**
     * 获取应用脱敏列表。
     *
     * @param appList 应用列表
     * @return 应用脱敏列表
     */
    List<AppVO> getAppVOList(List<App> appList);
}
