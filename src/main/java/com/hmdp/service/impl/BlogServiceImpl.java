package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollBlog;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 罗蓉鑫
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result quertHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
//        this::queryBlogUser
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //查询笔记
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //查询笔记的作者
        queryBlogUser(blog);
        //查询用户是否已经点赞过该笔记
        isBlogLiked(blog);
        return Result.ok(blog);
    }
    //判断某个用户是否已经点赞，并且赋值给blog
    private void isBlogLiked(Blog blog) {
        //1.获得当前登录的用户
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            //如果用户没有登录直接返回
            return;
        }
        //2.判断当前用户是否已经点过赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), String.valueOf(userDTO.getId()));
        //3.赋值给笔记
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获得当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前用户是否已经点过赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        //3.如果未点赞，该笔记点赞数 + 1
        if(score == null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId + "",System.currentTimeMillis());
            }
        }else {
            //4.如果已点赞，该笔记点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId + "");
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询点赞排行前5用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //得到top5的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //将top的用户id以,分隔
        String strIds = StrUtil.join(",",ids);
        //查询top5的用户 并且转化成userDao
        // ！！！
        // listByIds(list) 使用的查询语句为WHERE id IN ( 1010 ,5 ) 查询出的结果并不按照in里面的顺序，而是默认id的顺序
        // 正解:WHERE id IN ( 1010 ,5 )  order by field(id,1010,5)
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids)
                .last("order by field(id," + strIds + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if(isSuccess){
            //3.查询到关注该用户的所有粉丝
            List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
            List<Long> ids = follows.stream().map(Follow::getUserId).collect(Collectors.toList());
            for (Long id : ids) {
              //4.给每个粉丝的收件箱推消息
              stringRedisTemplate.opsForZSet()
                      .add(FEED_KEY + id,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result rollQueryOfFollow(Long minTime, Integer offset) {
        //1.查询当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //2.查询当前用户的收件箱 zrevrangebyscore k 6 0 withscores limit 2 3
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, minTime, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析收件箱的数据，ids，minTime,os(下一次查询的偏移量) 关键难点1在于os的获取
        int os = 1;
        List<String> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            ids.add(tuple.getValue());
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据ids查询出所有的笔记  难点2在于需要保证查询不乱序
        String strIds = StrUtil.join(",", ids);
        List<Blog> blogs = query().inSql("id", strIds).last("order by field(id," + strIds + ")").list();
        for (Blog blog : blogs) {
            //难度3在于需要吧笔记相关的作者，用户点赞信息一并查询出来
            //查询笔记的作者
            queryBlogUser(blog);
            //查询用户是否已经点赞过该笔记
            isBlogLiked(blog);
        }
        //5.封装数据，进行返回
        ScrollBlog scrollBlog = new ScrollBlog();
        scrollBlog.setList(blogs);
        scrollBlog.setOffset(os);
        scrollBlog.setMinTime(minTime);
        return Result.ok(scrollBlog);
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
