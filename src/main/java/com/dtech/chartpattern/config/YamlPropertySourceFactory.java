package com.dtech.chartpattern.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.util.Properties;

public class YamlPropertySourceFactory implements PropertySourceFactory {
    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        Resource res = resource.getResource();
        factory.setResources(res);
        Properties properties = factory.getObject();
        String sourceName = (name != null ? name : res.getFilename());
        return new PropertiesPropertySource(sourceName, properties != null ? properties : new Properties());
    }
}
