package com.example.ems.web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
public record EmployeeReturn(String keyword,Long departmentId,Integer status,int page,int pageSize) {
    public static EmployeeReturn from(HttpServletRequest r){
        try {
            String keyword=r.getParameter("returnKeyword"),dept=r.getParameter("returnDepartmentId"),status=r.getParameter("returnStatus");
            int page=number(r.getParameter("returnPage"),1),size=number(r.getParameter("returnPageSize"),20);
            return new EmployeeReturn(keyword==null?"":keyword.trim(),dept==null||dept.isBlank()?null:Long.valueOf(dept),status==null||status.isBlank()?null:Integer.valueOf(status),Math.max(1,page),size==50||size==100?size:20);
        }catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"返回页的筛选参数格式不正确");}
    }
    private static int number(String value,int fallback){return value==null||value.isBlank()?fallback:Integer.parseInt(value);}
    public String url(){return UriComponentsBuilder.fromPath("/employees").queryParam("keyword",keyword).queryParamIfPresent("departmentId",java.util.Optional.ofNullable(departmentId)).queryParamIfPresent("status",java.util.Optional.ofNullable(status)).queryParam("page",page).queryParam("pageSize",pageSize).build().encode().toUriString();}
}
