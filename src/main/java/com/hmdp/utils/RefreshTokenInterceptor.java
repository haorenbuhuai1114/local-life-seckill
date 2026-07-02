package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取token
        String token = request.getHeader("authorization");
        //2. 校验token是否为空
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //3. 获取用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(key);
        //4. 验证用户是否为空
        if (user.isEmpty()) {
            return true;
        }
        //5. 将用户保存到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), false);
        //6. 存放到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7. 刷新token有效期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
