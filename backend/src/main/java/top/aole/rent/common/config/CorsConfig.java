package top.aole.rent.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。开发期前端 5175 直连后端 8082(不走 vite proxy 时);
 * 生产由网关统一收口(网关剥离/重注入 X-User-* 头,见 ADR-001 · S0-04)。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 允许业务读到占位身份头(生产由可信网关注入)
                .exposedHeaders("X-User-Name", "X-User-Role")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
