package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.HashMap;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //这边只需要判断是否token信息对的.
        if( UserHolder.getUser() == null){
            log.info("登录信息未通过");
            return false;
        }
        return true;

    }
}
