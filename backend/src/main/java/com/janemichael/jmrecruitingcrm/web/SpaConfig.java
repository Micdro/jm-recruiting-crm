package com.janemichael.jmrecruitingcrm.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the built React app from classpath:/static (the Docker build copies it there).
 * Any path that isn't a file or an API call returns index.html so React Router can handle it.
 *
 * Built files under /assets have a content hash in their name, so browsers keep them for a year
 * and never ask again. Everything else (index.html, the manifest) is revalidated on each load so
 * a new release shows up immediately.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    private static final String STATIC = "classpath:/static/";
    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.equals("api")) {
                            return null;
                        }

                        if (!resourcePath.isEmpty() && !resourcePath.endsWith("/")) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable() && isFile(resourcePath)) {
                                return requested;
                            }
                            if (isFile(resourcePath)) {
                                // A missing asset should 404, not return the app.
                                return null;
                            }
                        }

                        return INDEX.exists() ? INDEX : null;
                    }
                });
    }

    /** Paths whose last segment has an extension, like assets/index-abc.js or favicon.svg. */
    private static boolean isFile(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.contains(".");
    }
}
