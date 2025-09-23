package com.example.audio_upload_web.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CommonController {

    @GetMapping("/index")
    public ModelAndView index(){
        return new ModelAndView("index");
    }


    @GetMapping("/")
    public ModelAndView realTime(){
        return new ModelAndView("real-time");
    }


    @GetMapping("/compression")
    public ModelAndView compression(){
        return new ModelAndView("compression");
    }


    @GetMapping("/rtc")
    public ModelAndView realTimeCompression(){ return new ModelAndView("real-time-compression"); }
}
