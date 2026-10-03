package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShiroConfig {

    @Bean
    public Realm localRealm() {
        return new ColdChainRealm();
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/error", "anon");
        // 冷链台账接口与管理页面使用 HTTP Basic，便于 curl/联调；浏览器会弹出登录框
        chain.addPathDefinition("/api/**", "authcBasic");
        chain.addPathDefinition("/ui/**", "authcBasic");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
