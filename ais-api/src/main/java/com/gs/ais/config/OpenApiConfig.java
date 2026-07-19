package com.gs.ais.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ais API 文档")
                        .description("""
                                ## ais 文生图应用接口文档

                                ### 功能概览

                                **供应商管理** — 配置和管理 LLM 服务供应商（对话模型 / 图像生成模型）

                                **会话管理** — 创建、切换、删除多轮对话会话

                                **对话消息** — 发送聊天消息，自动附带历史上下文。上下文超过 128k token 时自动压缩

                                **图像生成** — 基于对话内容生成图像，支持多种图像格式（PNG/JPEG/GIF/WebP）

                                **文件上传** — 支持上传图片和文档作为对话附件
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("ais")
                                .url("https://github.com/your-org/ais"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:11111").description("本地开发服务器"),
                        new Server().url("/").description("同源部署")))
                .components(new Components());
    }
}
