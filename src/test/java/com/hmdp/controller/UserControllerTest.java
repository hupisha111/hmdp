package com.hmdp.controller;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void userByIdReturnsPublicDtoInsteadOfPrivateUserFields() throws Exception {
        when(userMapper.selectById(2L)).thenReturn(new User().setId(2L)
                .setNickName("被访问的用户").setIcon("/icons/2.jpg")
                .setPhone("13800000000").setPassword("private-password"));

        mockMvc.perform(get("/user/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.nickName").value("被访问的用户"))
                .andExpect(jsonPath("$.data.icon").value("/icons/2.jpg"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void missingUserReturnsBusinessError() throws Exception {
        when(userMapper.selectById(999L)).thenReturn(null);
        mockMvc.perform(get("/user/999"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("用户不存在！"));
    }

    @Test
    void malformedUserIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/user/undefined")).andExpect(status().isBadRequest());
        verifyNoInteractions(userMapper);
    }

    @Test
    void existingMeRouteStillReturnsLoggedInUser() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
        mockMvc.perform(get("/user/me"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));
        verifyNoInteractions(userMapper);
    }
}
