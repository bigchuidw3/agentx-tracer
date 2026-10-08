package com.agentx.tracer.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Sa-Token 配置：注册全局拦截器，对除登录接口、静态资源外的所有接口做登录校验。
 * <p>
 * 纯登录模型，无角色/权限区分，所有用户可见页面一致。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 静态资源扩展名（命中后跳过登录校验）
     */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "html", "js", "css", "map",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "woff", "woff2", "ttf", "eot",
            "json", "txt", "md"
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 全局登录校验：排除登录接口、根路径（交给静态资源处理器转发到 index.html）、静态资源
            SaRouter.match("/**")
                    .notMatch("/auth/**")
                    .notMatch("/")
                    .check(r -> {
                        if (isStaticResource()) {
                            return;
                        }
                        StpUtil.checkLogin();
                    });
        })).addPathPatterns("/**");
    }

    /**
     * 判断当前请求是否是静态资源（按扩展名）。
     */
    private boolean isStaticResource() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        int dotIndex = uri.lastIndexOf('.');
        if (dotIndex == -1) {
            return false;
        }
        String ext = uri.substring(dotIndex + 1).toLowerCase();
        return STATIC_EXTENSIONS.contains(ext);
    }
}
