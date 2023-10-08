package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;

@SpringBootTest(classes = HmDianPingApplication.class)
public class UserAddTest {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void name() throws IOException {
        List<User> users = userService.list();

        FileWriter fw = new FileWriter("token.txt");

        for (User user : users) {
            String phone = user.getPhone();
            userService.sendMessage(phone, null);
            String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            LoginFormDTO loginFormDTO = new LoginFormDTO();
            loginFormDTO.setPhone(phone);
            loginFormDTO.setCode(code);
            Result result = userService.login(loginFormDTO, null);
            String token = (String) result.getData();
            fw.write(token + "\n");
        }
    }
}
