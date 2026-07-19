package com.gs.ais.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/admin", "/feishu", "/admin/**", "/feishu/**"})
    public String index() {
        return "forward:/index.html";
    }
}
