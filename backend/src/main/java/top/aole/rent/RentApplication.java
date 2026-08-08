package top.aole.rent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 沃朗科技租赁板块后端入口(智慧园区平台内)。
 * {@code @EnableScheduling}:开启定时任务(后续 M2 收租单生成/M3 折旧计提/账期缺口扫描等 cron)。
 * 包名 top.aole.rent · 端口 8082 · context-path /api · 库 worland_dev · 表前缀 yc_rent_。
 */
@EnableScheduling
@SpringBootApplication
public class RentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RentApplication.class, args);
    }
}
