package com.agentx.tracer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路由：固定进入欢迎页（登录/体验入口在欢迎页点「立即体验」新开 Tab）。
 *
 * @author agentx-console
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "redirect:/welcome.html";
    }
}
