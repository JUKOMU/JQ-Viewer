package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.dto.DesktopDtos;

/** Desktop 登录态与在线认证业务接口。 */
public interface DesktopAuthService {
    DesktopDtos.UserInfo login(String username, String password);

    DesktopDtos.Success logout();

    DesktopDtos.LoginState checkLoginState();

    DesktopDtos.UserProfile getUserProfile(String uid);
}
