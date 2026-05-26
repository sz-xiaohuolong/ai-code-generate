package com.xhl.aicodegenerate.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.xhl.aicodegenerate.constant.UserConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.ThrowUtils;
import com.xhl.aicodegenerate.mapper.AppMapper;
import com.xhl.aicodegenerate.mapper.ChatHistoryMapper;
import com.xhl.aicodegenerate.model.dto.chat.ChatHistoryQueryRequest;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import com.xhl.aicodegenerate.model.vo.ChatHistoryVO;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对话历史 服务层实现。
 *
 * @author <a href="https://github.com/sz-xiaohuolong">不会喷火的小火龙</a>
 */
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> implements ChatHistoryService {

    private static final int APP_CHAT_HISTORY_PAGE_SIZE = 10;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "messageType", "appId", "userId", "createTime", "updateTime"
    );

    @Resource
    private AppMapper appMapper;

    @Resource
    private ChatMemoryStore chatMemoryStore;

    @Override
    public void validChatHistory(ChatHistory chatHistory, boolean add) {
        if (chatHistory == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String message = chatHistory.getMessage();
        String messageType = chatHistory.getMessageType();
        Long appId = chatHistory.getAppId();
        Long userId = chatHistory.getUserId();
        if (add) {
            ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息不能为空");
            ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
            ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
            ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        }
        if (StrUtil.isNotBlank(messageType) && ChatHistoryMessageTypeEnum.getEnumByValue(messageType) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型错误");
        }
    }

    @Override
    public Long saveMessage(Long appId, Long userId, String message, String messageType) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessage(message);
        chatHistory.setMessageType(messageType);
        validChatHistory(chatHistory, true);
        boolean result = this.save(chatHistory);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "保存对话历史失败");
        return chatHistory.getId();
    }

    @Override
    public boolean deleteByAppId(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        // 先删 Redis，再删 DB。若 DB 删除失败，后续仍可从 DB 懒加载恢复 Redis；
        // 反过来先删 DB 则可能因 Redis 删除失败导致旧对话复活。
        chatMemoryStore.deleteMessages(appId);
        boolean result = this.remove(QueryWrapper.create().eq("appId", appId));
        return result;
    }

    @Override
    public QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        if (chatHistoryQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = chatHistoryQueryRequest.getId();
        String message = chatHistoryQueryRequest.getMessage();
        String messageType = chatHistoryQueryRequest.getMessageType();
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        String sortField = chatHistoryQueryRequest.getSortField();
        String sortOrder = chatHistoryQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("messageType", messageType)
                .eq("appId", appId)
                .eq("userId", userId)
                .like("message", message);
        if (chatHistoryQueryRequest.getLastCreateTime() != null) {
            queryWrapper.lt("createTime", chatHistoryQueryRequest.getLastCreateTime());
        }
        if (StrUtil.isNotBlank(sortField) && ALLOWED_SORT_FIELDS.contains(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }

    /**
     * 获取应用对话历史
     * @param chatHistoryQueryRequest 查询请求
     * @param loginUser               当前登录用户
     * @return
     */
    @Override
    public Page<ChatHistoryVO> listAppChatHistoryVOByPage(ChatHistoryQueryRequest chatHistoryQueryRequest, User loginUser) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        Long appId = chatHistoryQueryRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        int pageSize = chatHistoryQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > APP_CHAT_HISTORY_PAGE_SIZE, ErrorCode.PARAMS_ERROR, "每次最多加载 10 条");
        App app = appMapper.selectOneByQuery(QueryWrapper.create().eq("id", appId));
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppChatHistoryAuth(app, loginUser);
        chatHistoryQueryRequest.setPageNum(1);
        chatHistoryQueryRequest.setUserId(null);
        QueryWrapper queryWrapper = this.getQueryWrapper(chatHistoryQueryRequest)
                .orderBy("createTime", false)
                .orderBy("id", false);
        Page<ChatHistory> chatHistoryPage = this.page(Page.of(1, pageSize), queryWrapper);
        Page<ChatHistoryVO> chatHistoryVOPage = buildChatHistoryVOPage(chatHistoryPage, 1, pageSize);
        List<ChatHistoryVO> records = chatHistoryVOPage.getRecords();
        Collections.reverse(records);
        chatHistoryVOPage.setRecords(records);
        return chatHistoryVOPage;
    }

    @Override
    public Page<ChatHistoryVO> listChatHistoryVOByPageByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        QueryWrapper queryWrapper = this.getQueryWrapper(chatHistoryQueryRequest)
                .orderBy("createTime", false)
                .orderBy("id", false);
        Page<ChatHistory> chatHistoryPage = this.page(Page.of(pageNum, pageSize), queryWrapper);
        return buildChatHistoryVOPage(chatHistoryPage, pageNum, pageSize);
    }

    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        if (chatHistory == null) {
            return null;
        }
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        BeanUtil.copyProperties(chatHistory, chatHistoryVO);
        return chatHistoryVO;
    }

    @Override
    public List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList) {
        if (CollUtil.isEmpty(chatHistoryList)) {
            return new ArrayList<>();
        }
        return chatHistoryList.stream().map(this::getChatHistoryVO).collect(Collectors.toList());
    }

    private void checkAppChatHistoryAuth(App app, User loginUser) {
        if (!app.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private Page<ChatHistoryVO> buildChatHistoryVOPage(Page<ChatHistory> chatHistoryPage, long pageNum, long pageSize) {
        Page<ChatHistoryVO> chatHistoryVOPage = new Page<>(pageNum, pageSize, chatHistoryPage.getTotalRow());
        List<ChatHistoryVO> chatHistoryVOList = this.getChatHistoryVOList(chatHistoryPage.getRecords());
        chatHistoryVOPage.setRecords(chatHistoryVOList);
        return chatHistoryVOPage;
    }
}
