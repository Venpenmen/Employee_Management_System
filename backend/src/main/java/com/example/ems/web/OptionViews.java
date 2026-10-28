package com.example.ems.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OptionViews {
    private OptionViews(){}
    public static List<Map<String,Object>> departments(List<Map<String,Object>> rows,boolean executive){
        return executive?rows:select(rows,"id","department_name","status");
    }
    public static List<Map<String,Object>> positions(List<Map<String,Object>> rows,boolean executive){
        return executive?rows:select(rows,"id","department_id","position_name","is_manager_position","status");
    }
    public static List<Map<String,Object>> dashboard(List<Map<String,Object>> rows,boolean executive){
        return executive?rows:select(rows,"id","department_name","status","manager_name","manager_no","employee_count");
    }
    private static List<Map<String,Object>> select(List<Map<String,Object>> rows,String... fields){
        return rows.stream().map(row->{
            Map<String,Object> output=new LinkedHashMap<>();
            for(String field:fields)output.put(field,row.get(field));
            return output;
        }).toList();
    }
}
