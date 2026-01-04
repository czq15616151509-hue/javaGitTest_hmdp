package com.hmdp.config;

import com.hmdp.utils.InterceptorLogin;
import com.hmdp.utils.InterceptorRefreshToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author chen
 * @function 拦截器的配置类
 * @date 2025/10/2
 */
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {
    @Autowired
    private InterceptorRefreshToken interceptorRefreshToken;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //配置拦截器InterceptorLogin
        registry.addInterceptor(new InterceptorLogin())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/upload/**",
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**"
                ).order(2);
        //配置拦截器InterceptorRefreshToken
        registry.addInterceptor(interceptorRefreshToken)
                .addPathPatterns("/**").order(1);
    }
}
