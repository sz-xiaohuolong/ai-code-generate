package com.xhl.aicodegenerate.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.model.dto.user.UserQueryRequest;
import com.xhl.aicodegenerate.model.vo.LoginUserVO;
import com.xhl.aicodegenerate.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.DigestUtils;

import java.util.List;

/**
 * 用户 服务层。
 *
 * @author <a href="https://github.com/sz-xiaohuolong">不会喷火的小火龙</a>
 */
public interface UserService extends IService<User> {


    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return
     */
    LoginUserVO getLoginUserVO(User user);


    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);


    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    UserVO getUserVO(User user);

    List<UserVO> getUserVOList(List<User> userList);

    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);

    String getEncryptPassword(String userPassword);
}
