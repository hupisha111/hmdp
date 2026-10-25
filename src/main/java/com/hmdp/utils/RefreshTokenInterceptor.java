package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.从request中取出header的token
        String token = request.getHeader("authorization");
        //2.拿token去redis查这个user的信息.
        String tokenKey = LOGIN_USER_KEY + token;
        //这里加个val这个数据类型来接收为什么不会报错,它是什么意思? val自动定义为应当的属性.
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(tokenKey);
        //然后判断这个entries是否存在
         //null 和 isEmpty()是什么区别 ?为什么entries不可能是null
        if(entries == null || entries.isEmpty()) {
            return true;
        }
        log.info("用户token信息正确");
        //查到了redis保存的用户信息,需要把什么保存给线程使用呢.线程需要user信息,我取出来的是一个map.
        UserDTO user = new UserDTO();
        BeanUtil.fillBeanWithMap(entries, user, false);
        //保存到线程中.
        UserHolder.saveUser(user);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;


    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
