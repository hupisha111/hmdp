package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate today = LocalDate.now();
        String key = USER_SIGN_KEY + userId + ":" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
        // Bitmap 的偏移从 0 开始，当月第几天就写入第几天减 1 的位置。
        stringRedisTemplate.opsForValue().setBit(key, today.getDayOfMonth() - 1, true);
        return Result.ok();
    }

    @Override
    public Result queryUserById(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在！");
        }
        return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    @Override
    public Result sendCode(String phone) {
        //第一步对手机号校验是否合规.
        if(RegexUtils.isPhoneInvalid(phone)) {
            //TODO不合规的话报错,抛出异常.这里是用result.fail()代替了抛出异常,如果抛出异常怎么写?
            return Result.fail("手机号格式错误");
        }
        log.info("手机号合规");
        //合规的话,调用工具生成验证码.现在改为验证码和对应手机号保存到redis.
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("验证码已保存到redis");
        //为什么不直接返回验证码给前端呢,因为这是要用短信发给用户的啊..
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session, HttpServletResponse response) {
        //1.取出手机号,验证码和密码.
        String phone = loginForm.getPhone();
        String preCode = loginForm.getCode();
        //2.首先要对手机号校验,是否格式符合?
        if(RegexUtils.isPhoneInvalid(phone)) {
            //3.不符合回Result.fail(),再加一个状态码
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return Result.fail("手机号格式不对");
        }
        //4.手机号没问题之后,要比较验证码,得去redis取出验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        //6.比较前端验证码和redis验证码是否一致
        if(code == null || !code.equals(preCode)){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return Result.fail("验证码不对");
        }
        log.info("手机号和验证码正确");
        // 根据手机号查询用户；不存在时创建并保存
        User user = query().eq("phone",phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 只把登录所需的用户信息（不含密码）写入 Redis 哈希
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        log.info("用户信息已保存到redis");

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

}
