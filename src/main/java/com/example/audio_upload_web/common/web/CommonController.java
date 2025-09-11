package com.example.audio_upload_web.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CommonController {

    @GetMapping("/")
    public ModelAndView index(){
        return new ModelAndView("index");
    }


    @GetMapping("/real-time")
    public ModelAndView realTime(){
        return new ModelAndView("real-time");
    }
}
