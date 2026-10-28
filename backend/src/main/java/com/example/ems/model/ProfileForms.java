package com.example.ems.model;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfileForms {
    private ProfileForms() {}
    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";
    public record Basic(
        @NotBlank(message="手机号不能为空") @Pattern(regexp="^1[3-9]\\d{9}$",message="手机号应为有效的十一位号码") String phone,
        @Email(message="邮箱格式不正确") @Size(max=100,message="邮箱最多100字") String email,
        @Size(max=255,message="地址最多255字") String address,
        @Size(max=50,message="紧急联系人最多50字") String emergencyName,
        @Size(max=30,message="联系人关系最多30字") String emergencyRelation,
        @Pattern(regexp="^$|^1[3-9]\\d{9}$",message="紧急联系电话应为十一位手机号") String emergencyPhone,
        @Size(max=1000,message="个人简介最多1000字") String biography,
        @Pattern(regexp="^$|^[01]$",message="性别选项无效") String gender,
        @NotNull(message="缺少档案版本，请刷新") @Min(value=0,message="档案版本无效") Long version
    ) {
        public Map<String,Object> values() {
            return fields("email",email,"address",address,"emergency_name",emergencyName,
                "emergency_relation",emergencyRelation,"emergency_phone",emergencyPhone,"biography",biography,"gender",gender);
        }
    }
    public sealed interface Entry permits Education, Experience, Skill, Certificate {
        Map<String,Object> values();
    }
    public record Education(
        @NotBlank(message="学校不能为空") @Size(max=100) String school,
        @NotBlank(message="学历不能为空") @Size(max=30) String degree,
        @Size(max=100) String major,
        @NotBlank(message="入学月份不能为空") @Pattern(regexp=MONTH,message="起始月份格式不正确") String startMonth,
        @Pattern(regexp="^$|"+MONTH,message="结束月份格式不正确") String endMonth,
        Boolean currentEntry
    ) implements Entry {
        public Map<String,Object> values() {
            validateMonths(startMonth,endMonth,currentEntry);
            return fields("school",school,"degree",degree,"major",major,"start_month",startMonth,
                "end_month",endMonth,"current_entry",Boolean.TRUE.equals(currentEntry)?1:0);
        }
    }
    public record Experience(
        @NotBlank(message="单位不能为空") @Size(max=100) String company,
        @Size(max=100) String department, @Size(max=100) String position,
        @NotBlank(message="开始月份不能为空") @Pattern(regexp=MONTH,message="起始月份格式不正确") String startMonth,
        @Pattern(regexp="^$|"+MONTH,message="结束月份格式不正确") String endMonth,
        Boolean currentEntry,
        @Size(max=1000,message="工作描述最多1000字") String description
    ) implements Entry {
        public Map<String,Object> values() {
            validateMonths(startMonth,endMonth,currentEntry);
            return fields("company",company,"department",department,"position",position,"start_month",startMonth,
                "end_month",endMonth,"current_entry",Boolean.TRUE.equals(currentEntry)?1:0,"description",description);
        }
    }
    public record Skill(
        @NotBlank(message="技能名称不能为空") @Size(max=100) String name,
        @NotBlank(message="请选择熟练程度") @Pattern(regexp="了解|熟悉|熟练|精通",message="熟练程度无效") String proficiency,
        @Size(max=500,message="技能说明最多500字") String notes
    ) implements Entry {
        public Map<String,Object> values() { return fields("name",name,"proficiency",proficiency,"notes",notes); }
    }
    public record Certificate(
        @NotBlank(message="证书名称不能为空") @Size(max=100) String name,
        @NotBlank(message="颁发机构不能为空") @Size(max=100) String issuer,
        @Size(max=100) String certificateNumber,
        @NotBlank(message="取得日期不能为空") String obtainedDate, String expiryDate
    ) implements Entry {
        public Map<String,Object> values() {
            try {
                LocalDate obtained=LocalDate.parse(obtainedDate);
                LocalDate expiry=blank(expiryDate)?null:LocalDate.parse(expiryDate);
                if(expiry!=null&&expiry.isBefore(obtained)) throw new IllegalArgumentException("到期日期不得早于取得日期");
                return fields("name",name,"issuer",issuer,"certificate_number",certificateNumber,
                    "obtained_date",obtained,"expiry_date",expiry);
            } catch(java.time.DateTimeException ex) { throw new IllegalArgumentException("证书日期格式不正确"); }
        }
    }
    public static boolean blank(String value) { return value==null||value.isBlank(); }
    public static String text(Object value) { return value==null?"":value.toString(); }
    private static Map<String,Object> fields(Object... pairs) {
        Map<String,Object> values=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) {
            Object value=pairs[i+1];
            if(value instanceof String s) value=s.isBlank()?null:s.trim();
            values.put((String)pairs[i],value);
        }
        return values;
    }
    private static void validateMonths(String start,String end,Boolean current) {
        try {
            YearMonth first=YearMonth.parse(start);
            if(Boolean.TRUE.equals(current)&&!blank(end)) throw new IllegalArgumentException("在读或在职记录不能同时填写结束月份");
            if(!Boolean.TRUE.equals(current)&&blank(end)) throw new IllegalArgumentException("请填写结束月份，或勾选在读／在职");
            if(!blank(end)&&YearMonth.parse(end).isBefore(first)) throw new IllegalArgumentException("结束月份不得早于开始月份");
        } catch(java.time.DateTimeException ex) { throw new IllegalArgumentException("经历月份格式不正确"); }
    }
}
