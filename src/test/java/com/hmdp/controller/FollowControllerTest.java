package com.hmdp.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.impl.FollowServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FollowControllerTest {

    @Mock
    private FollowMapper followMapper;
    @Mock
    private IUserService userService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Follow.class);
        FollowServiceImpl followService = new FollowServiceImpl();
        ReflectionTestUtils.setField(followService, "baseMapper", followMapper);
        ReflectionTestUtils.setField(followService, "userService", userService);
        ReflectionTestUtils.setField(followService, "stringRedisTemplate", stringRedisTemplate);
        FollowController controller = new FollowController();
        ReflectionTestUtils.setField(controller, "followService", followService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void followInsertsCurrentUserAndTargetUser() throws Exception {
        login();
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(followMapper.selectCount(any())).thenReturn(0);
        when(followMapper.insert(any())).thenReturn(1);

        mockMvc.perform(put("/follow/2/true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<Follow> relation = ArgumentCaptor.forClass(Follow.class);
        verify(followMapper).insert(relation.capture());
        assertEquals(Long.valueOf(42), relation.getValue().getUserId());
        assertEquals(Long.valueOf(2), relation.getValue().getFollowUserId());
        verify(setOperations).add("follows:42", "2");
    }

    @Test
    void followingAnAlreadyFollowedUserDoesNotInsertAgain() throws Exception {
        login();
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(followMapper.selectCount(any())).thenReturn(1);

        mockMvc.perform(put("/follow/2/true"))
                .andExpect(jsonPath("$.success").value(true));

        verify(followMapper, never()).insert(any());
        verify(setOperations).add("follows:42", "2");
    }

    @Test
    void failedInsertReturnsBusinessError() throws Exception {
        login();
        when(followMapper.selectCount(any())).thenReturn(0);
        when(followMapper.insert(any())).thenReturn(0);

        mockMvc.perform(put("/follow/2/true"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("关注失败，请重试！"));
        verifyNoInteractions(stringRedisTemplate);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void unfollowOnlyDeletesTheCurrentUsersMatchingRelation(int affectedRows) throws Exception {
        login();
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(followMapper.delete(any())).thenReturn(affectedRows);

        mockMvc.perform(put("/follow/2/false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<QueryWrapper<Follow>> condition = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(followMapper).delete(condition.capture());
        assertMatchesCurrentUserAndTarget(condition.getValue());
        verify(followMapper, never()).insert(any());
        verify(setOperations).remove("follows:42", "2");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void queryReturnsBooleanForCurrentUserAndTarget(int count) throws Exception {
        login();
        when(followMapper.selectCount(any())).thenReturn(count);

        mockMvc.perform(get("/follow/or/not/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(count > 0));

        ArgumentCaptor<QueryWrapper<Follow>> condition = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(followMapper).selectCount(condition.capture());
        assertMatchesCurrentUserAndTarget(condition.getValue());
    }

    @Test
    void anonymousUserCannotChangeFollowRelations() throws Exception {
        mockMvc.perform(put("/follow/2/true"))
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(put("/follow/2/false"))
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(followMapper);
    }

    @Test
    void anonymousUserCannotQueryFollowState() throws Exception {
        mockMvc.perform(get("/follow/or/not/2"))
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(followMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/follow/invalid/true", "/follow/2/invalid"})
    void invalidFollowParametersReturnBadRequest(String path) throws Exception {
        login();
        mockMvc.perform(put(path)).andExpect(status().isBadRequest());
        verifyNoInteractions(followMapper);
    }

    @Test
    void invalidQueryUserIdReturnsBadRequest() throws Exception {
        login();
        mockMvc.perform(get("/follow/or/not/invalid")).andExpect(status().isBadRequest());
        verifyNoInteractions(followMapper);
    }

    @Test
    void commonFollowReturnsOnlyUserDtoFieldsForRedisIntersection() throws Exception {
        login();
        when(stringRedisTemplate.hasKey("follows:42")).thenReturn(true);
        when(stringRedisTemplate.hasKey("follows:2")).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.intersect("follows:42", "follows:2"))
                .thenReturn(new HashSet<>(Arrays.asList("7", "9")));
        when(userService.list(any())).thenReturn(Arrays.asList(
                new User().setId(7L).setNickName("共同关注7").setIcon("/7.jpg")
                        .setPhone("13800000000").setPassword("private-password"),
                new User().setId(9L).setNickName("共同关注9").setIcon("/9.jpg")));

        mockMvc.perform(get("/follow/common/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].nickName").value("共同关注7"))
                .andExpect(jsonPath("$.data[0].icon").value("/7.jpg"))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
        ArgumentCaptor<QueryWrapper<User>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userService).list(query.capture());
        query.getValue().getSqlSegment();
        assertEquals(new HashSet<>(Arrays.asList(7L, 9L)),
                new HashSet<>(query.getValue().getParamNameValuePairs().values()));
        verifyNoInteractions(followMapper);
    }

    @Test
    void emptyCommonFollowReturnsEmptyArrayWithoutQueryingUsers() throws Exception {
        login();
        when(stringRedisTemplate.hasKey("follows:42")).thenReturn(true);
        when(stringRedisTemplate.hasKey("follows:2")).thenReturn(true);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.intersect("follows:42", "follows:2")).thenReturn(Collections.emptySet());

        mockMvc.perform(get("/follow/common/2"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verifyNoInteractions(userService);
    }

    @Test
    void missingRedisSetsAreRebuiltFromHistoricalDatabaseFollows() throws Exception {
        login();
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(followMapper.selectList(any())).thenReturn(
                Arrays.asList(new Follow().setFollowUserId(2L), new Follow().setFollowUserId(7L)),
                Arrays.asList(new Follow().setFollowUserId(7L), new Follow().setFollowUserId(9L)));
        when(setOperations.intersect("follows:42", "follows:2")).thenReturn(Collections.singleton("7"));
        when(userService.list(any())).thenReturn(Collections.singletonList(new User().setId(7L)));

        mockMvc.perform(get("/follow/common/2"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(7));
        verify(setOperations).add("follows:42", "2", "7");
        verify(setOperations).add("follows:2", "7", "9");
        ArgumentCaptor<QueryWrapper<Follow>> queries = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(followMapper, org.mockito.Mockito.times(2)).selectList(queries.capture());
        queries.getAllValues().forEach(query -> query.getSqlSegment());
        assertEquals(Collections.singleton(42L),
                new HashSet<>(queries.getAllValues().get(0).getParamNameValuePairs().values()));
        assertEquals(Collections.singleton(2L),
                new HashSet<>(queries.getAllValues().get(1).getParamNameValuePairs().values()));
    }

    @Test
    void addingFollowAlsoLoadsOlderRelationsWhenRedisWasMissing() throws Exception {
        login();
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(followMapper.selectCount(any())).thenReturn(0);
        when(followMapper.insert(any())).thenReturn(1);
        when(followMapper.selectList(any())).thenReturn(Arrays.asList(
                new Follow().setFollowUserId(7L), new Follow().setFollowUserId(2L)));

        mockMvc.perform(put("/follow/2/true"))
                .andExpect(jsonPath("$.success").value(true));
        verify(setOperations).add("follows:42", "7", "2");
        verify(setOperations).add("follows:42", "2");
    }

    @Test
    void anonymousUserCannotQueryCommonFollows() throws Exception {
        mockMvc.perform(get("/follow/common/2"))
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(followMapper, stringRedisTemplate, userService);
    }

    @Test
    void undefinedCommonFollowIdReturnsBadRequest() throws Exception {
        login();
        mockMvc.perform(get("/follow/common/undefined")).andExpect(status().isBadRequest());
        verifyNoInteractions(followMapper, stringRedisTemplate, userService);
    }

    private void login() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }

    private void assertMatchesCurrentUserAndTarget(QueryWrapper<Follow> condition) {
        String sql = condition.getSqlSegment();
        assertTrue(sql.contains("user_id ="));
        assertTrue(sql.contains("follow_user_id ="));
        assertTrue(sql.contains(" AND "));
        assertEquals(2, condition.getParamNameValuePairs().size());
        assertEquals(new HashSet<>(Arrays.asList(42L, 2L)),
                new HashSet<>(condition.getParamNameValuePairs().values()));
    }
}
