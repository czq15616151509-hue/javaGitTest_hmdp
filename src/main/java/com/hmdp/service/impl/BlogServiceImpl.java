package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.RollingQueryResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Autowired
    private StringHttpMessageConverter stringHttpMessageConverter;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //首页hot笔记
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            //前端需要显示作品发布者部分信息
            queryBlogAuthor(blog);
            //设置前端点赞按钮亮色还是暗色
            blogIsLiked(blog);
        });
        return Result.ok(records);
    }

    //根据id查询笔记(笔记内容、用户信息、点赞信息<hot5>)
    @Override
    public Result queryBlogById(Long id) {
        //1 查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        //2 前端需要显示作品发布者部分信息
        queryBlogAuthor(blog);

        //3 笔记是否被当前用户点过赞(判断之后,对isLike字段赋值),前端根据blog中的isLike字段来决定显示点赞按钮亮色还是暗色
        blogIsLiked(blog);

        return Result.ok(blog);  //里面有笔记信息，有用户脱敏后的信息，还有点赞信息
    }

    //设置前端显示页面的作者信息（图标等）
    private void queryBlogAuthor(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    //（该方法用于用户查询某一个笔记详情时，需要显示的点赞情况）判断笔记是否被本用户点过赞,从而对isLike进行赋值，因为前端根据blog中的isLike字段来决定显示点赞按钮亮色还是暗色
    private void blogIsLiked(Blog blog) {
        //1 获取登录用户 (用户没登录，直接返回)
        Long userId = UserHolder.getUser().getId();
        if (userId == null){
            //没登录就直接返回，不在继续当前用户查询点赞情况
            return;
        }
        //2 判断当前用户是否已经点过赞
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + userId, userId.toString());
        blog.setIsLike(score != null);
    }

    //(增加点赞或取消点赞)实现当前用户点赞功能，再次点赞即取消功能（一人一赞）
    //使用redis的sortedSet集合，来保存笔记和点赞用户id的信息
    @Override
    public Result   likeBlog(Long id) {
        //1 获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2 判断当前用户是否已经点过赞(查询redis)
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
        if (score == null){
            //3 如果未点过赞，则可以点赞
            //3.1 数据库点赞+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 保存用户id到redis sortedSet集合中,zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        }else{
            //4 如果点过赞，则再次点击是取消点赞
            //4.1 数据库点赞-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 移除用户id从redis sortedSet集合中
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    //笔记详情页面的点赞处，显示前五个点赞的用户（top5）
    @Override
    public Result queryBlogLikes(Long id) {
        //1 从redis中查询当前笔记的点赞用户top5用户即前五个先点赞的用户，zrange key 0 4
        Set<String> Top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (Top5 == null || Top5.isEmpty()){
            //如果为空，直接返回空List集合
            return Result.ok(Collections.emptyList());
        }
        //2 解析出这些用户id, String->Long
        List<Long> ids = Top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3 根据用户id查询用户信息，进行脱敏
        List<UserDTO> userDTOs = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4 返回
        return Result.ok(userDTOs);
    }

    //查看朋友圈
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2 查询当前用户的收件箱————按照score降序查询，查询数量是2，
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                //max是上一次查询结果中最后一条笔记的时间戳即本次查询的最大score，本次查询的最小score设置为0,2是查询数量，offset是偏移量(代表上一次分页查询中最小score的个数)
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3 判断收件箱非空
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4 解析数据:blogId、minTime、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int offsetForNext = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取blogId
            ids.add(Long.valueOf(typedTuple.getValue()));
            //获取时间戳
            long time = typedTuple.getScore().longValue();
            if (time == minTime){
                offsetForNext++;
            }else{
                minTime = time;
                offsetForNext = 1;
            }
        }
        //5 根据解析出的博客id，查询blog信息(保留ids的顺序)
        String idsStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        for(Blog blog : blogs){
            //前端需要显示作品发布者部分信息
            queryBlogAuthor(blog);
            //设置前端点赞按钮亮色还是暗色
            blogIsLiked(blog);
        }
        //6 封装并且返回
        RollingQueryResult rollingQueryResult = new RollingQueryResult();
        rollingQueryResult.setList(blogs);
        rollingQueryResult.setMinTime(minTime);
        rollingQueryResult.setOffset(offsetForNext);
        return Result.ok(rollingQueryResult);
    }
}
