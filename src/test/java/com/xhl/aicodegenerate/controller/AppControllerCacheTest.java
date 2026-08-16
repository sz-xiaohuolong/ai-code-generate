package com.xhl.aicodegenerate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.model.dto.app.AppQueryRequest;
import com.xhl.aicodegenerate.model.vo.AppVO;
import com.xhl.aicodegenerate.service.AppService;
import com.xhl.aicodegenerate.service.ProjectDownloadService;
import com.xhl.aicodegenerate.service.UserService;
import com.xhl.aicodegenerate.utils.CacheKeyUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(AppControllerCacheTest.TestConfig.class)
class AppControllerCacheTest {

    @Autowired
    private AppController controller;

    @Autowired
    private AppService appService;

    @Autowired
    private UserService userService;

    @Autowired
    private CacheManager cacheManager;

    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("featuredApp").clear();
        reset(appService, userService);
        request = mock(HttpServletRequest.class);
        User user = new User();
        user.setId(1L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(appService.getQueryWrapper(any(AppQueryRequest.class))).thenReturn(QueryWrapper.create());
        Page<App> databasePage = new Page<>(1, 6, 0);
        databasePage.setRecords(List.of());
        when(appService.page(any(Page.class), any(QueryWrapper.class))).thenReturn(databasePage);
        when(appService.getAppVOList(any())).thenReturn(List.of());
    }

    @Test
    void shouldCacheEqualFeaturedQueriesWithinFirstTenPages() {
        controller.listFeaturedAppVOByPage(query(1), request);
        controller.listFeaturedAppVOByPage(query(1), request);

        verify(appService, times(1)).page(any(Page.class), any(QueryWrapper.class));
        verify(userService, times(1)).getLoginUser(request);
    }

    @Test
    void shouldBypassCacheAfterPageTen() {
        controller.listFeaturedAppVOByPage(query(11), request);
        controller.listFeaturedAppVOByPage(query(11), request);

        verify(appService, times(2)).page(any(Page.class), any(QueryWrapper.class));
        verify(userService, times(2)).getLoginUser(request);
    }

    @Test
    void shouldReturnWarmCacheWithoutEnteringControllerBody() {
        BaseResponse<Page<AppVO>> warmResponse = controller.listFeaturedAppVOByPage(query(1), request);
        reset(userService, appService);
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        BaseResponse<Page<AppVO>> cachedResponse = controller.listFeaturedAppVOByPage(query(1), request);

        assertThat(cachedResponse).usingRecursiveComparison().isEqualTo(warmResponse);
        verifyNoInteractions(userService, appService);
    }

    private AppQueryRequest query(int pageNum) {
        AppQueryRequest query = new AppQueryRequest();
        query.setPageNum(pageNum);
        query.setPageSize(6);
        query.setSortField("createTime");
        query.setSortOrder("descend");
        query.setAppName("");
        return query;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        CacheKeyUtils cacheKeyUtils(ObjectMapper objectMapper) {
            return new CacheKeyUtils(objectMapper);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("featuredApp");
        }

        @Bean
        AppService appService() {
            return mock(AppService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        ProjectDownloadService projectDownloadService() {
            return mock(ProjectDownloadService.class);
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        AppController appController() {
            return new AppController();
        }
    }
}
