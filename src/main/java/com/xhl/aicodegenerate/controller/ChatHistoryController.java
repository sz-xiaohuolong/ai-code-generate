package com.xhl.aicodegenerate.controller;

import com.mybatisflex.core.paginate.Page;
import com.xhl.aicodegenerate.annotation.AuthCheck;
import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.common.ResultUtils;
import com.xhl.aicodegenerate.constant.UserConstant;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.ThrowUtils;
import com.xhl.aicodegenerate.model.dto.chat.ChatHistoryQueryRequest;
import com.xhl.aicodegenerate.model.vo.ChatHistoryVO;
import com.xhl.aicodegenerate.service.ChatHistoryService;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史 控制层。
 *
 * @author <a href="https://github.com/sz-xiaohuolong">不会喷火的小火龙</a>
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private UserService userService;

    /**
     * 分页查询某个应用的对话历史。
     */
    @PostMapping("/app/list/page/vo")
    public BaseResponse<Page<ChatHistoryVO>> listAppChatHistoryVOByPage(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest,
                                                                         HttpServletRequest request) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(chatHistoryService.listAppChatHistoryVOByPage(chatHistoryQueryRequest, loginUser));
    }

    /**
     * 管理员分页查询所有对话历史。
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistoryVO>> listChatHistoryVOByPageByAdmin(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(chatHistoryService.listChatHistoryVOByPageByAdmin(chatHistoryQueryRequest));
    }

}
