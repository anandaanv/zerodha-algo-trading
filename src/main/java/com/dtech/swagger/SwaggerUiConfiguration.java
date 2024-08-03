package com.dtech.swagger;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@jakarta.annotation.Generated(value = "io.com.dtech.swagger.codegen.v3.generators.java.SpringCodegen", date = "2020-12-24T18:29:06.738Z[GMT]")
@Configuration
public class SwaggerUiConfiguration implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.
                addResourceHandler("/com.dtech.swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-com.dtech.swagger-ui/")
                .resourceChain(false);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/com.dtech.swagger-ui/").setViewName("forward:/com.dtech.swagger-ui/index.html?configUrl=/v3/api-docs");
    }
}