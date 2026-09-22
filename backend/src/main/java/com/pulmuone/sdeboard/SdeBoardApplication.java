package com.pulmuone.sdeboard;

import com.pulmuone.sdeboard.domain.AppTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SdeBoardApplication {
    public static void main(String[] args) {
        // ⚠️ 스프링 기동 **전에** 고정한다 — 컨테이너 기본이 UTC 라 우리가 찍는 관측 시각이
        //    9시간 이르게 저장되고 있었다. 자세한 경위는 AppTime 주석.
        AppTime.applyAsJvmDefault();
        SpringApplication.run(SdeBoardApplication.class, args);
    }
}
