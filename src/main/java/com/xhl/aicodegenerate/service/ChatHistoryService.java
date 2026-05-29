package com.xhl.aicodegenerate.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.xhl.aicodegenerate.entity.ChatHistory;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.model.dto.chat.ChatHistoryQueryRequest;
import com.xhl.aicodegenerate.model.vo.ChatHistoryVO;

import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/sz-xiaohuolong">不会喷火的小火龙</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 校验对话历史。
     *
     * @param chatHistory 对话历史
     * @param add 是否为创建校验
     */
    void validChatHistory(ChatHistory chatHistory, boolean add);

    /**
     * 保存消息。
     *
     * @param appId 应用 id
     * @param userId 用户 id
     * @param message 消息
     * @param messageType 消息类型
     * @return 消息 id
     */
    Long saveMessage(Long appId, Long userId, String message, String messageType);

    /**
     * 更新历史消息内容。
     *
     * @param id 消息 id
     * @param message 新消息内容
     * @return 是否成功
     */
    boolean updateMessage(Long id, String message);

    /**
     * 根据应用 id 删除历史消息。
     *
     * @param appId 应用 id
     * @return 是否成功
     */
    boolean deleteByAppId(Long appId);

    /**
     * 获取查询条件。
     *
     * @param chatHistoryQueryRequest 查询请求
     * @return 查询条件
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 分页查询某个应用的对话历史。
     *
     * @param chatHistoryQueryRequest 查询请求
     * @param loginUser 当前登录用户
     * @return 对话历史分页
     */
    Page<ChatHistoryVO> listAppChatHistoryVOByPage(ChatHistoryQueryRequest chatHistoryQueryRequest, User loginUser);

    /**
     * 管理员分页查询所有对话历史。
     *
     * @param chatHistoryQueryRequest 查询请求
     * @return 对话历史分页
     */
    Page<ChatHistoryVO> listChatHistoryVOByPageByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 获取脱敏信息。
     *
     * @param chatHistory 对话历史
     * @return 对话历史脱敏信息
     */
    ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory);

    /**
     * 获取脱敏列表。
     *
     * @param chatHistoryList 对话历史列表
     * @return 对话历史脱敏列表
     */
    List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> chatHistoryList);
}
