package com.fons.cloud.ai.trip.infrastructure.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 必选提示词加载器。按完整 classpath 路径读取并缓存原始内容。
 * 缓存随应用重启刷新，不对提示词进行变量替换或格式化。
 */
public class PromptLoader {

    private static final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载必选提示词，例如 {@code prompt/master_agent_sys_prompt.md}。
     *
     * @param classpathPath 相对 classpath 根目录的完整路径，不包含 {@code classpath:} 前缀
     * @return UTF-8 解码的提示词原文
     * @throws IllegalArgumentException 路径为空、为绝对路径或包含相对路径跳转
     * @throws IllegalStateException 资源不存在、无法读取或内容为空白
     */
    public static String loadRequired(String classpathPath) {
        if (classpathPath == null || classpathPath.isBlank()) {
            throw new IllegalArgumentException("Required prompt path must not be blank");
        }
        if (classpathPath.startsWith("/") || classpathPath.contains("\\")
                || classpathPath.contains(":")
                || Arrays.stream(classpathPath.split("/", -1))
                .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Invalid classpath prompt path: " + classpathPath);
        }
        return cache.computeIfAbsent(classpathPath, PromptLoader::readRequired);
    }

    private static String readRequired(String classpathPath) {
        try (InputStream input = new ClassPathResource(classpathPath).getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException("Required prompt is blank: classpath:" + classpathPath);
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load required prompt: classpath:" + classpathPath, e);
        }
    }
}
