package com.example.ems.web;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component("viewFormats")
public class ViewFormats {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm");
    public String money(Object value){
        if(value==null)return "未设置";
        BigDecimal amount=value instanceof BigDecimal d?d:new BigDecimal(value.toString());
        DecimalFormat format=new DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return "¥"+format.format(amount);
    }
    public String date(Object value){
        if(value==null)return "—";
        if(value instanceof java.sql.Date d)return DATE.format(d.toLocalDate());
        if(value instanceof LocalDate d)return DATE.format(d);
        if(value instanceof LocalDateTime d)return DATE.format(d);
        return DATE.format(asDateTime(value));
    }
    public String dateTime(Object value){return value==null?"—":DATE_TIME.format(asDateTime(value));}
    public String time(Object value){
        if(value==null)return "—";
        if(value instanceof java.sql.Time t)return TIME.format(t.toLocalTime());
        if(value instanceof LocalTime t)return TIME.format(t);
        return TIME.format(LocalTime.parse(value.toString()));
    }
    public String greeting(){return greeting(LocalTime.now(ZONE));}
    public String greeting(LocalTime time){int hour=time.getHour();return hour>=6&&hour<12?"早上好":hour>=12&&hour<18?"下午好":"晚上好";}
    private LocalDateTime asDateTime(Object value){
        if(value instanceof LocalDateTime d)return d;
        if(value instanceof java.sql.Timestamp d)return d.toLocalDateTime();
        if(value instanceof java.util.Date d)return LocalDateTime.ofInstant(d.toInstant(),ZONE);
        if(value instanceof OffsetDateTime d)return d.atZoneSameInstant(ZONE).toLocalDateTime();
        if(value instanceof Instant d)return LocalDateTime.ofInstant(d,ZONE);
        return LocalDateTime.parse(value.toString().replace(' ','T'));
    }
}
