package com.hmdp.utils;
import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author chen
 * @function 拦截器
 * @date 2025/10/2
 */
public class InterceptorLogin implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //取出用户信息
        UserDTO user = UserHolder.getUser();
        //判断用户是否存在
        if (user == null){
            response.setStatus(401);
            return false;
        }
        //有用户，放行
        return true;
    }
}
