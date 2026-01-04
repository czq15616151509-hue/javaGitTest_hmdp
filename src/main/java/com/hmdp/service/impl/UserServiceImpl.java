package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    //生成验证码并存入redis缓存，实现session共享
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.验证手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");


        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //TODO 3.保存验证码到session（未用缓存）
//        session.setAttribute("code",code);
        //3.保存验证码到Redis,代替上述策略
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码给客户端，这里采用模拟发送
        log.info("发送短信验证码成功，验证码：{}",code);
        //5.返回ok
        return Result.ok();
    }

    //登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.验证手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误！");
        }
//        //TODO 2.验证验证码（未用缓存）
//        String cacheCode = (String) session.getAttribute("code");
//        String code = loginForm.getCode();
//        if(cacheCode == null || !cacheCode.equals(code)){
//            return Result.fail("验证码错误！");
//        }
        //2.从redis中取，验证验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY +loginForm.getPhone());
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误！");
        }
        //3.根据手机号查询用户,这里使用了mybatis plus的query方法
        User user = query().eq("phone", loginForm.getPhone()).one();
        //用户是否存在
        if (user == null){
            //不存在，创建新用户并保存
            user = createByPhone(loginForm.getPhone());
        }
//        //TODO 4.保存用户到session;注意我们要对用户进行脱敏，最后保存的是UserDTO（未用缓存）
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //4.保存用户到Redis，代替上述策略
        String token = UUID.randomUUID().toString(); // token是随机字符串，作为redis中用户信息的key
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class); //信息脱敏
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString())); //转换成map,注意redis只能存string值，不能存long类型
        String tokenKey = RedisConstants.LOGIN_USER_KEY +token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap); //保存用户信息
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES); //设置有效期
        //5.返回token
        return Result.ok(token);
    }

    //签到功能（某一个月的某一天）（使用redis中的BitMap结构————位图）
    @Override
    public Result sign() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3 拼接reids的key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + yyyyMM;
        //4 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5 在位图中把这一天对应的位置（bit）设为1; SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth,true);
        return Result.ok();
    }

    //连续签到统计（到今日为止已经连续签到了多少天)
    @Override
    public Result continuousSignCount() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3 拼接reids的key
        String yyyyMM = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + yyyyMM;
        //4 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5 获取本月截止至今天所有的签到记录，获取一个十进制的数字
        List<Long> results_10 = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(results_10 == null || results_10.isEmpty()){
            return Result.ok(0);
        }
        Long num_10 = results_10.get(0);
        if(num_10 == null || num_10 == 0L){
            return Result.ok(0);
        }
        //6 循环遍历: 十进制数与1不断做与运算，从而不断获得最后一位的bit值，进而判断是否满足连续签到
        int count = 0;
        while(true){
            //6。1 判断最后一位的bit值是否为0
            if ((num_10 & 1) == 0){
                //6.2 如果为0，说明未签到，结束
                break;
            }else{
                //6.3 如果为1，说明已签到，计数器+1；同时把十进制数右移一位
                count++;
                num_10 = num_10 >> 1;
            }
        }
        return Result.ok(count);
    }

    private User createByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+RandomUtil.randomString(10));
        //使用mybatis plus
        save(user);
        return user;
    }
}




