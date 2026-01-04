package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @功能：关注和取关功能，以及查询关注状态
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;
    private final IUserService userServiceImpl;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate, UserServiceImpl userServiceImpl) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userServiceImpl = userServiceImpl;
    }

    //关注，取关操作,(为什么我们要使用redis————目的是后续业务需要查询共同关注，Set集合有直接查询交集的指令)
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;
        //2 判断到底是关注操作，还是取消关注
        if (isFollow){
            //2.1 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                //把要关注的目标用户id添加到Redis中
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            //2.2 取关
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess){
                //把已经关注得目标用户id从Redis中移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    //查询关注状态
    @Override
    public Result queryIsFollow(Long followUserId) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2 查询关注的状态
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3 返回状态结果，count>0表示关注，否则未关注
        return Result.ok(count > 0);
    }

    //查询共同关注(redis中Set集合可以求交集)
    @Override
    public Result followCommons(Long id) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2 查询共同关注（redis的Set求交集操作）
        String key = RedisConstants.FOLLOW_KEY + userId;
        String key2 = RedisConstants.FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4 查询用户
        List<UserDTO> userDTOs = userServiceImpl.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //5 返回结果
        return Result.ok(userDTOs);
    }
}
