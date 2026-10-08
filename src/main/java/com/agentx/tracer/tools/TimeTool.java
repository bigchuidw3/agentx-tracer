package com.agentx.tracer.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具：提供当前日期、时间、星期、时区、时间戳等基础时间信息。
 */
@Component
public class TimeTool {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Tool(name = "currentTime",
            description = "获取当前日期、时间、星期、时区、Unix 时间戳等基础时间信息。" +
                    "当用户问题涉及「今天/现在/当前/最近/本月/本年/本周/上个月/下周一」等相对时间概念时必须调用，" +
                    "确保后续日期计算、时间范围圈定、时间差推理都基于真实当前时间，避免幻觉日期。")
    public String currentTime() {
        ZonedDateTime now = ZonedDateTime.now();
        ZoneId zone = now.getZone();
        String weekdayZh = switch (now.getDayOfWeek()) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
        return String.format(
                "当前时间信息：%n" +
                        "- 完整日期时间: %s%n" +
                        "- 日期: %s%n" +
                        "- 时间: %s%n" +
                        "- 星期: %s（一周第 %d 天）%n" +
                        "- 时区: %s（UTC%s）%n" +
                        "- 当月: %s%n" +
                        "- 当年: %d%n" +
                        "- Unix 时间戳（秒）: %d",
                now.format(DATETIME_FMT),
                now.format(DATE_FMT),
                now.format(TIME_FMT),
                weekdayZh, now.getDayOfWeek().getValue(),
                zone.getId(), now.getOffset().getId(),
                now.format(MONTH_FMT),
                now.getYear(),
                now.toEpochSecond()
        );
    }
}
