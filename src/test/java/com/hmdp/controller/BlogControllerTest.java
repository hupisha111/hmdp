package com.hmdp.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.BlogServiceImpl;
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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BlogControllerTest {

    @Mock
    private BlogMapper blogMapper;
    @Mock
    private IUserService userService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Blog.class);
        BlogServiceImpl blogService = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(blogService, "userService", userService);
        ReflectionTestUtils.setField(blogService, "stringRedisTemplate", stringRedisTemplate);
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", blogService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void queryBlogReturnsDetailsAndAuthor() throws Exception {
        Blog blog = new Blog().setId(5L).setUserId(1L).setShopId(2L)
                .setTitle("探店笔记").setContent("笔记正文").setImages("/imgs/blog/test.jpg")
                .setLiked(3).setComments(2).setCreateTime(LocalDateTime.of(2026, 10, 28, 12, 0));
        when(blogMapper.selectById(5L)).thenReturn(blog);
        when(userService.getById(1L)).thenReturn(new User().setId(1L)
                .setNickName("作者昵称").setIcon("/imgs/icons/test.jpg"));

        mockMvc.perform(get("/blog/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.shopId").value(2))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.title").value("探店笔记"))
                .andExpect(jsonPath("$.data.content").value("笔记正文"))
                .andExpect(jsonPath("$.data.images").value("/imgs/blog/test.jpg"))
                .andExpect(jsonPath("$.data.liked").value(3))
                .andExpect(jsonPath("$.data.isLike").value(false))
                .andExpect(jsonPath("$.data.comments").value(2))
                .andExpect(jsonPath("$.data.createTime").exists())
                .andExpect(jsonPath("$.data.name").value("作者昵称"))
                .andExpect(jsonPath("$.data.icon").value("/imgs/icons/test.jpg"));
    }

    @Test
    void queryMissingBlogReturnsBusinessError() throws Exception {
        when(blogMapper.selectById(999L)).thenReturn(null);

        mockMvc.perform(get("/blog/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("笔记不存在！"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(userService);
    }

    @Test
    void queryBlogWithMissingAuthorStillReturnsDetails() throws Exception {
        when(blogMapper.selectById(5L)).thenReturn(new Blog().setId(5L).setUserId(1L));
        when(userService.getById(1L)).thenReturn(null);

        mockMvc.perform(get("/blog/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void queryBlogRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/blog/invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(blogMapper, userService);
    }

    @Test
    void clickingTwiceAddsThenRemovesLike() throws Exception {
        login();
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:5", "42")).thenReturn(null, 1234D);
        when(blogMapper.update(isNull(), any())).thenReturn(1);

        long beforeClick = System.currentTimeMillis();
        mockMvc.perform(put("/blog/like/5"))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(put("/blog/like/5"))
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<UpdateWrapper<Blog>> updates = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(blogMapper, org.mockito.Mockito.times(2)).update(isNull(), updates.capture());
        assertEquals("liked = liked + 1", updates.getAllValues().get(0).getSqlSet());
        assertEquals("liked = liked - 1", updates.getAllValues().get(1).getSqlSet());
        updates.getAllValues().forEach(update -> {
            assertEquals("(id = #{ew.paramNameValuePairs.MPGENVAL1})", update.getSqlSegment());
            assertEquals(5L, update.getParamNameValuePairs().get("MPGENVAL1"));
        });
        ArgumentCaptor<Double> timestamp = ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).add(eq("blog:liked:5"), eq("42"), timestamp.capture());
        assertTrue(timestamp.getValue() >= beforeClick);
        assertTrue(timestamp.getValue() <= System.currentTimeMillis());
        verify(zSetOperations).remove("blog:liked:5", "42");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDatabaseUpdateDoesNotChangeRedis(boolean liked) throws Exception {
        login();
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:999", "42")).thenReturn(liked ? 1234D : null);
        when(blogMapper.update(isNull(), any())).thenReturn(0);

        mockMvc.perform(put("/blog/like/999"))
                .andExpect(jsonPath("$.success").value(false));

        verify(zSetOperations, never()).add(any(), any(String.class), anyDouble());
        verify(zSetOperations, never()).remove(any(), any(Object.class));
    }

    @Test
    void anonymousUserCannotLike() throws Exception {
        mockMvc.perform(put("/blog/like/5"))
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(blogMapper, stringRedisTemplate);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detailsIncludeCurrentUsersLikeState(boolean liked) throws Exception {
        login();
        when(blogMapper.selectById(5L)).thenReturn(new Blog().setId(5L).setUserId(1L));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:5", "42")).thenReturn(liked ? 1234D : null);

        mockMvc.perform(get("/blog/5"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isLike").value(liked));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/blog/hot", "/blog/of/me"})
    void listsIncludeLikeStateForEveryBlog(String path) throws Exception {
        login();
        stubBlogPage();
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:5", "42")).thenReturn(1234D);
        when(zSetOperations.score("blog:liked:6", "42")).thenReturn(null);

        mockMvc.perform(get(path))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].isLike").value(true))
                .andExpect(jsonPath("$.data[1].id").value(6))
                .andExpect(jsonPath("$.data[1].isLike").value(false));
    }

    @Test
    void anonymousHotListReturnsFalseWithoutReadingRedis() throws Exception {
        stubBlogPage();
        mockMvc.perform(get("/blog/hot"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].isLike").value(false))
                .andExpect(jsonPath("$.data[1].isLike").value(false));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void likesEndpointReturnsFirstFiveUsersInLikeOrderWithoutPrivateFields() throws Exception {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("blog:liked:5", 0, 4))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("9", "3", "7", "1", "5")));
        when(userService.list(any())).thenReturn(Arrays.asList(
                likingUser(9L), likingUser(3L), likingUser(7L), likingUser(1L), likingUser(5L)));

        mockMvc.perform(get("/blog/likes/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[1].id").value(3))
                .andExpect(jsonPath("$.data[2].id").value(7))
                .andExpect(jsonPath("$.data[3].id").value(1))
                .andExpect(jsonPath("$.data[4].id").value(5))
                .andExpect(jsonPath("$.data[0].nickName").value("用户9"))
                .andExpect(jsonPath("$.data[0].icon").value("/icons/9.jpg"))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());

        ArgumentCaptor<QueryWrapper<User>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(userService).list(query.capture());
        assertTrue(query.getValue().getSqlSegment().endsWith("ORDER BY FIELD(id,9,3,7,1,5)"));
        assertEquals("id,nick_name,icon", query.getValue().getSqlSelect());
        verify(zSetOperations).range("blog:liked:5", 0, 4);
    }

    @Test
    void likesEndpointReturnsEmptyArrayWhenNobodyLiked() throws Exception {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("blog:liked:5", 0, 4)).thenReturn(Collections.emptySet());

        mockMvc.perform(get("/blog/likes/5"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        verifyNoInteractions(userService);
    }

    @Test
    void likesEndpointReturnsAvailableUsersWhenSomeAccountsAreMissing() throws Exception {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("blog:liked:5", 0, 4))
                .thenReturn(new LinkedHashSet<>(Arrays.asList("9", "3", "7")));
        when(userService.list(any())).thenReturn(Arrays.asList(likingUser(9L), likingUser(7L)));

        mockMvc.perform(get("/blog/likes/5"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[1].id").value(7));
    }

    @Test
    void zeroScoreStillMeansUserHasLiked() throws Exception {
        login();
        when(blogMapper.selectById(5L)).thenReturn(new Blog().setId(5L).setUserId(1L));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:5", "42")).thenReturn(0D);

        mockMvc.perform(get("/blog/5"))
                .andExpect(jsonPath("$.data.isLike").value(true));
    }

    @Test
    void userBlogListFiltersByVisitedUserAndUsesViewersLikeState() throws Exception {
        login();
        when(blogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<Blog> page = invocation.getArgument(0);
            page.setRecords(Collections.singletonList(new Blog().setId(5L).setUserId(2L)));
            return page;
        });
        when(userService.getById(2L)).thenReturn(new User().setId(2L).setNickName("被访问的作者"));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("blog:liked:5", "42")).thenReturn(1234D);

        mockMvc.perform(get("/blog/of/user").param("id", "2").param("current", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].userId").value(2))
                .andExpect(jsonPath("$.data[0].name").value("被访问的作者"))
                .andExpect(jsonPath("$.data[0].isLike").value(true));

        ArgumentCaptor<QueryWrapper<Blog>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        ArgumentCaptor<Page<Blog>> page = ArgumentCaptor.forClass(Page.class);
        verify(blogMapper).selectPage(page.capture(), query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id ="));
        assertEquals(Collections.singleton(2L),
                new java.util.HashSet<>(query.getValue().getParamNameValuePairs().values()));
        assertEquals(2, page.getValue().getCurrent());
        assertEquals(10, page.getValue().getSize());
    }

    @Test
    void userWithoutBlogsReturnsEmptyArrayAndDefaultsToFirstPage() throws Exception {
        when(blogMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        mockMvc.perform(get("/blog/of/user").param("id", "2"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
        ArgumentCaptor<Page<Blog>> page = ArgumentCaptor.forClass(Page.class);
        verify(blogMapper).selectPage(page.capture(), any());
        assertEquals(1, page.getValue().getCurrent());
        verifyNoInteractions(userService, stringRedisTemplate);
    }

    @Test
    void userBlogListRequiresNumericUserId() throws Exception {
        mockMvc.perform(get("/blog/of/user")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/blog/of/user").param("id", "undefined"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(blogMapper);
    }

    private User likingUser(Long id) {
        return new User().setId(id).setNickName("用户" + id).setIcon("/icons/" + id + ".jpg")
                .setPhone("13800000000").setPassword("private-password");
    }

    private void login() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }

    private void stubBlogPage() {
        when(blogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            IPage<Blog> page = invocation.getArgument(0);
            page.setRecords(Arrays.asList(new Blog().setId(5L).setUserId(42L),
                    new Blog().setId(6L).setUserId(42L)));
            return page;
        });
    }
}
