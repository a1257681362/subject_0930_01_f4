package com.evops.config;

import com.evops.common.UserPrincipal;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.realm.AuthenticatingRealm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冷链台账内置演示账号：登录主体为 UserPrincipal（携带操作者 ID）。
 * V1 为本地静态账号，后续版本可对接统一身份。
 */
public class ColdChainRealm extends AuthenticatingRealm {

    /** username -> (id, password) */
    private static final Map<String, long[]> IDS = new LinkedHashMap<>();
    private static final Map<String, String> PASSWORDS = new LinkedHashMap<>();

    static {
        put("bootstrap", 1L, "bootstrap");
        put("dispatcher", 2L, "dispatcher123");   // 调度/派车
        put("carrier", 3L, "carrier123");         // 承运/在途上报
        put("receiver", 4L, "receiver123");       // 收货签收
    }

    private static void put(String username, long id, String password) {
        IDS.put(username, new long[]{id});
        PASSWORDS.put(username, password);
    }

    public ColdChainRealm() {
        // SimpleCredentialsMatcher 会把 HTTP Basic 提交的 char[] 密码转成字符串后比对
        setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = token.getPrincipal() == null ? null : String.valueOf(token.getPrincipal());
        if (username == null || !PASSWORDS.containsKey(username)) {
            return null;
        }
        UserPrincipal principal = new UserPrincipal(IDS.get(username)[0], username);
        // 凭证由 SimpleCredentialsMatcher 与 token 中的 char[] 密码比对
        return new SimpleAuthenticationInfo(principal, PASSWORDS.get(username), getName());
    }
}
