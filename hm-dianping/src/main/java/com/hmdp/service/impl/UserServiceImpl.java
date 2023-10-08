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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

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
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendMessage(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机格式不正确");
        }

        String code = RandomUtil.randomNumbers(6);

        // 改成redis的存储形式
//        session.setAttribute("code", code);
//        session.setAttribute("phone", phone);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,
                LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送短信成功: " + code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
//        String basePhone = (String) session.getAttribute("phone");
//        String baseCode = (String) session.getAttribute("code");

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请检查手机号格式！");
        }

        // 从redis里取出数据
        String code = loginForm.getCode();
        String codeCache = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if (StringUtils.isEmpty(code) || !code.equals(codeCache)) {
            return Result.fail("请检查验证码！");
        }

        User userOld = this.query().eq("phone", phone).one();

        if (userOld == null) {
            userOld = createUserWithPhone(phone);
        }
        UserDTO user = BeanUtil.copyProperties(userOld, UserDTO.class);

//        session.setAttribute("user", user);
        // 以token为key存进redis
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;

        Map<String, Object> userMap = BeanUtil.beanToMap(user, new HashMap<>(),
                CopyOptions.create().ignoreNullValue()
                        .setFieldValueEditor((fieldName, valueName) -> (valueName.toString())));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 返回token给页面
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        // 获取年月日，这里yyyyMM前面加了 : 别忽略了，目的是为了redis key的格式
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int day = now.getDayOfMonth();

        String key = USER_SIGN_KEY + userId + date;
        // 记得把日期-1，索引从0开始
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        // 获取年月日，这里yyyyMM前面加了 : 别忽略了，目的是为了redis key的格式
        String date = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int day = now.getDayOfMonth();
        String key = USER_SIGN_KEY + userId + date;

        List<Long> list = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                        .valueAt(0));

        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }

        Long num = list.get(0);

        if (num == null || num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        while (true){
            if ((num & 1) == 1) {
                count++;
            }else{
                break;
            }
            num >>>= 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        this.save(user);

        return user;
    }
}
