package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
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
import static com.hmdp.utils.RedisConstants.FEED_COUNT;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 查询博文，不存在时返回业务错误，避免后续空指针异常。
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        String key = BLOG_LIKED_KEY + id;
        String userId = user.getId().toString();
        boolean liked = stringRedisTemplate.opsForZSet().score(key, userId) != null;

        // 数据库更新成功后，再同步 Redis 中的点赞用户集合。
        boolean success = update(new UpdateWrapper<Blog>()
                .setSql(liked ? "liked = liked - 1" : "liked = liked + 1")
                .eq("id", id));
        if (!success) {
            return Result.fail("点赞操作失败，笔记可能不存在！");
        }
        if (liked) {
            stringRedisTemplate.opsForZSet().remove(key, userId);
        } else {
            stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 按点赞时间升序，取最早点赞的五位用户（结束下标包含 4）。
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // IN 只筛选用户，FIELD 保证数据库返回顺序与 Redis 中的点赞顺序一致。
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<UserDTO> users = userService.list(new QueryWrapper<User>()
                        .select("id", "nick_name", "icon")
                        .in("id", ids)
                        .last("ORDER BY FIELD(id," + idStr + ")"))
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        Page<Blog> page = query().eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::isBlogLiked);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        Page<Blog> page = query().eq("user_id", userId)
                .orderByDesc("create_time").orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query().orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //入参就是一个blog.需要的工作有saveblog2DB,发送到粉丝的收件箱(Redis).
        //1.保存到数据库
        final Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        final boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("博客发送失败");
        }
        //2.推送到粉丝的收件箱
        //2.1获取粉丝(follow表)
        final List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        //2.2把blogId推送到scoreSet.
        for (Follow follow : follows) {
            final Long id = follow.getUserId();
            String key = "feed:" +  id;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog);
    }

    @Override
    public Result queryFollowBlog(Long lastTime, Integer offset) {
        //需要获取的数据:zset,userId.
        //入参是时间戳(score),用来定位这一次从zset的哪里开始取. offset用来判断跳过几个相同的值.
        //出参需要List<Blog>,lasttime和offset(用来给下一次查询提供值.)
        //1.拿到userId
        final Long userId = UserHolder.getUser().getId();
        //2.查询对应的redis存储的zset. 反转,依据值(最大值,最小值),offset跳过几个,count取几个(两个好吧).
        String key = RedisConstants.FEED_KEY + userId;
        final Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastTime, offset, FEED_COUNT);
        log.info("滚动分页查询了关注用户发布的博客");
        //3.需要对set进行处理
        //3.1把set的bolgId变为完整的blog返回.传回去list<Blog>就好了,不带score.lastTime单独返回.注意:blog的查询要按照我们的顺序,同时还要带上数据库没有的字段.
        //3.2最好在一次遍历中把lastTime和offset也确定下来.
        Long minTime = 0L;
        Integer os = 1;
        List<Blog> list = new ArrayList<>();
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            Long blogId = Long.valueOf(typedTuple.getValue());
            final Blog blog = query().eq("id", blogId).one();
            if(blog != null){
                list.add(blog);
            }
            if(minTime != typedTuple.getScore().longValue()) {
                minTime = typedTuple.getScore().longValue();
                os = 1;
            }
            else{
                os++;
            }
        }
        //4.封装拼接返回数据类ScrollResult.
        final ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(list);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        //5.return
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        // 昵称和头像不在博客表中，需要根据作者 ID 补充。
        User user = userService.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
        blog.setIsLike(score != null);
    }

}
