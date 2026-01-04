package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @功能：发布探店笔记，根据id查询笔记（查询的返回既要包含笔记信息又要包含用户信息），点赞功能（一人一赞）
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IFollowService followService;
    @Resource
    private IBlogService blogService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    //发布笔记（博客）,同时实现关注推送功能（推模式）
    //推模式：就是主播发送笔记时会把笔记推送到每个粉丝的收件箱中，粉丝浏览朋友圈时，朋友圈会按照时间戳依次显示所有关注人的笔记，收件箱需要用redis来实现而且还需要排序，所以选用了sortedSet
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        //1 发布新作品
        //1.1 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //1.2 保存探店博文
        boolean isSuccess = blogService.save(blog);
        if(!isSuccess){
            return Result.fail("发布笔记失败！");
        }

        //2 关注推送，推送给所有粉丝
        //2.1 查询笔记作者的所有粉丝(sql查询)
        List<Follow> fans = followService.query().eq("follow_id", user.getId()).list();
        //2.2 推送笔记的id给所有粉丝
        for (Follow fan : fans) {
            //获取粉丝id
            Long userId = fan.getUserId();
            //推送（找到粉丝的“收件箱”，把笔记推送到粉丝的“收件箱”/朋友圈）
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());  // System.currentTimeMillis()作为score
         }

        //3 返回id
        return Result.ok(blog.getId());
    }

    //点赞功能（实现一人只能点赞一次，再次点就是取消点赞）
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //首页hot笔记
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    //根据id查询笔记
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    //笔记详情页面的点赞处，显示前五个点赞的用户（top5）
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    //点击其他用户头像，显示该用户所有笔记信息
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //查看当前用户的朋友圈
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            //sortedSet按照score降序查询，时间戳作为score
            //max是上一次滚动分页查询的最后一条笔记的时间戳即score
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset)
    {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
