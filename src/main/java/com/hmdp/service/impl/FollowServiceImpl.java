package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        Long userId = user.getId();
        if (Boolean.TRUE.equals(isFollow)) {
            // 已有关系不重复插入，但仍补齐 Redis 中的关注信息。
            if (count(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId)) == 0) {
                Follow follow = new Follow().setUserId(userId).setFollowUserId(followUserId);
                if (!save(follow)) {
                    return Result.fail("关注失败，请重试！");
                }
            }
            ensureFollowSet(userId);
            stringRedisTemplate.opsForSet().add(FOLLOW_KEY + userId, followUserId.toString());
        } else {
            // 两个用户 ID 都要匹配，不能删除其他用户的关注关系。
            remove(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            ensureFollowSet(userId);
            stringRedisTemplate.opsForSet().remove(FOLLOW_KEY + userId, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        int count = count(new QueryWrapper<Follow>().eq("user_id", user.getId())
                .eq("follow_user_id", followUserId));
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long otherUserId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录！");
        }
        ensureFollowSet(user.getId());
        ensureFollowSet(otherUserId);
        Set<String> commonIds = stringRedisTemplate.opsForSet()
                .intersect(FOLLOW_KEY + user.getId(), FOLLOW_KEY + otherUserId);
        if (commonIds == null || commonIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = commonIds.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.list(new QueryWrapper<User>()
                        .select("id", "nick_name", "icon").in("id", ids).orderByAsc("id"))
                .stream().map(commonUser -> BeanUtil.copyProperties(commonUser, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    private void ensureFollowSet(Long userId) {
        String key = FOLLOW_KEY + userId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        // 兼容仅存在于数据库中的历史关注，以及 Redis 数据丢失的情况。
        List<String> ids = list(new QueryWrapper<Follow>()
                .select("follow_user_id").eq("user_id", userId))
                .stream().map(follow -> follow.getFollowUserId().toString())
                .collect(Collectors.toList());
        if (!ids.isEmpty()) {
            stringRedisTemplate.opsForSet().add(key, ids.toArray(new String[0]));
        }
    }

}
