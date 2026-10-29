package com.example.ems.service;

import com.example.ems.model.ProfileForms;
import com.example.ems.model.SessionUser;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProfileService {
    public enum Section {
        EDUCATION("education","employee_education","教育经历"),
        EXPERIENCE("experience","employee_experience","工作经历"),
        SKILLS("skills","employee_skill","技能"),
        CERTIFICATES("certificates","employee_certificate","证书");
        public final String route,table,label;
        Section(String route,String table,String label){this.route=route;this.table=table;this.label=label;}
        public static Section from(String route) {
            return Arrays.stream(values()).filter(s->s.route.equals(route)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"档案分类不存在"));
        }
    }
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("phone","手机号"),Map.entry("email","邮箱"),Map.entry("address","通讯地址"),
        Map.entry("emergency_name","紧急联系人"),Map.entry("emergency_relation","联系人关系"),
        Map.entry("emergency_phone","紧急联系电话"),Map.entry("biography","个人简介"),Map.entry("gender","性别"),
        Map.entry("school","学校"),Map.entry("degree","学历"),Map.entry("major","专业"),
        Map.entry("start_month","起始月份"),Map.entry("end_month","结束月份"),Map.entry("current_entry","在读／在职状态"),
        Map.entry("company","单位"),Map.entry("department","部门"),Map.entry("position","职位"),
        Map.entry("description","工作描述"),Map.entry("name","名称"),Map.entry("proficiency","熟练程度"),
        Map.entry("notes","说明"),Map.entry("issuer","颁发机构"),Map.entry("certificate_number","证书编号"),
        Map.entry("obtained_date","取得日期"),Map.entry("expiry_date","到期日期"));
    private final JdbcTemplate jdbc;
    private final LogService logs;
    private final Validator validator;
    public ProfileService(JdbcTemplate jdbc,LogService logs,Validator validator){this.jdbc=jdbc;this.logs=logs;this.validator=validator;}

    @Transactional(readOnly=true)
    public Map<String,Object> view(Long employeeId,SessionUser actor) {
        if(!actor.id().equals(employeeId)&&!actor.isExecutive())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权查看他人完整档案");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("basic",basic(employeeId));
        for(Section section:Section.values())
            result.put(section.route,jdbc.queryForList("SELECT * FROM "+section.table+" WHERE employee_id=? ORDER BY id DESC",employeeId));
        result.put("history",jdbc.queryForList("""
            SELECT create_time,operation_type,summary FROM operation_log
            WHERE target_type='EMPLOYEE_PROFILE' AND target_id=? ORDER BY id DESC LIMIT 100
            """,employeeId));
        return result;
    }
    public Map<String,Object> basic(Long employeeId) {
        return jdbc.queryForMap("""
            SELECT e.phone,p.email,p.address,p.emergency_name,p.emergency_relation,p.emergency_phone,
                   p.biography,p.gender,COALESCE(p.version,0) version
            FROM employee e LEFT JOIN employee_profile p ON p.employee_id=e.id WHERE e.id=?
            """,employeeId);
    }
    public ProfileForms.Basic basicForm(Long employeeId) {
        Map<String,Object> row=basic(employeeId);
        return new ProfileForms.Basic(s(row,"phone"),s(row,"email"),s(row,"address"),s(row,"emergency_name"),
            s(row,"emergency_relation"),s(row,"emergency_phone"),s(row,"biography"),s(row,"gender"),
            ((Number)row.get("version")).longValue());
    }
    public Map<String,Object> ownedEntry(Section section,Long id,SessionUser actor) {
        var rows=jdbc.queryForList("SELECT * FROM "+section.table+" WHERE id=? AND employee_id=?",id,actor.id());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或不属于您");
        return rows.get(0);
    }
    public Object entryForm(Section section,Long id,SessionUser actor) {
        Map<String,Object> row=id==null?Map.of():ownedEntry(section,id,actor);
        boolean current=row.get("current_entry") instanceof Number n&&n.intValue()==1;
        return switch(section) {
            case EDUCATION -> new ProfileForms.Education(s(row,"school"),s(row,"degree"),s(row,"major"),s(row,"start_month"),s(row,"end_month"),current);
            case EXPERIENCE -> new ProfileForms.Experience(s(row,"company"),s(row,"department"),s(row,"position"),s(row,"start_month"),s(row,"end_month"),current,s(row,"description"));
            case SKILLS -> new ProfileForms.Skill(s(row,"name"),s(row,"proficiency"),s(row,"notes"));
            case CERTIFICATES -> new ProfileForms.Certificate(s(row,"name"),s(row,"issuer"),s(row,"certificate_number"),s(row,"obtained_date"),s(row,"expiry_date"));
        };
    }
    @Transactional
    public void saveBasic(ProfileForms.Basic form,SessionUser actor) {
        validate(form);
        // Serialize saves for one person, then check the version supplied by the open form.
        jdbc.queryForObject("SELECT id FROM employee WHERE id=? FOR UPDATE",Long.class,actor.id());
        Map<String,Object> before=basic(actor.id());
        long version=((Number)before.get("version")).longValue();
        if(form.version()!=version)throw new ProfileConflictException("档案已被其他页面修改，请刷新后重新编辑；本次内容尚未保存");
        Map<String,Object> values=form.values();
        String phone=form.phone().trim();
        Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE phone=? AND id<>?",Integer.class,phone,actor.id());
        if(duplicate!=null&&duplicate>0)throw new IllegalArgumentException("该手机号已被其他员工使用");
        List<String> changed=new ArrayList<>();
        if(!s(before,"phone").equals(phone))changed.add("手机号");
        values.forEach((key,value)->{if(!s(before,key).equals(ProfileForms.text(value)))changed.add(LABELS.get(key));});
        try {
            jdbc.update("UPDATE employee SET phone=? WHERE id=?",phone,actor.id());
            if(version==0) {
                var args=new ArrayList<>(values.values());args.add(actor.id());
                jdbc.update("INSERT INTO employee_profile("+String.join(",",values.keySet())+",employee_id,version) VALUES("+placeholders(values.size())+",?,1)",args.toArray());
            } else {
                var args=new ArrayList<>(values.values());args.add(actor.id());args.add(version);
                if(jdbc.update("UPDATE employee_profile SET "+assignments(values)+",version=version+1 WHERE employee_id=? AND version=?",args.toArray())!=1)
                    throw new ProfileConflictException("档案版本冲突，请刷新后重新编辑");
            }
        } catch(DuplicateKeyException ex){throw new IllegalArgumentException("手机号或档案状态冲突，请刷新后重试");}
        logs.log(actor.id(),"PROFILE_UPDATE","EMPLOYEE_PROFILE",actor.id(),
            changed.isEmpty()?"保存个人档案（未更改内容）":"修改个人档案字段："+String.join("、",changed));
    }
    @Transactional
    public void saveEntry(Section section,Long id,ProfileForms.Entry form,SessionUser actor) {
        boolean compatible=switch(section) {
            case EDUCATION -> form instanceof ProfileForms.Education;
            case EXPERIENCE -> form instanceof ProfileForms.Experience;
            case SKILLS -> form instanceof ProfileForms.Skill;
            case CERTIFICATES -> form instanceof ProfileForms.Certificate;
        };
        if(!compatible)throw new IllegalArgumentException("档案表单类型不正确");
        validate(form);
        Map<String,Object> before=id==null?Map.of():ownedEntry(section,id,actor);
        Map<String,Object> values=form.values();
        var args=new ArrayList<>(values.values());
        args.add(actor.id());
        if(id==null) {
            jdbc.update("INSERT INTO "+section.table+"("+String.join(",",values.keySet())+",employee_id) VALUES("+placeholders(values.size())+",?)",args.toArray());
        } else {
            args.add(id);
            int count=jdbc.update("UPDATE "+section.table+" SET "+assignments(values)+" WHERE employee_id=? AND id=?",args.toArray());
            if(count!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或不属于您");
        }
        String changed=values.keySet().stream().filter(k->!s(before,k).equals(ProfileForms.text(values.get(k))))
            .map(LABELS::get).collect(Collectors.joining("、"));
        logs.log(actor.id(),id==null?"PROFILE_ENTRY_ADD":"PROFILE_ENTRY_UPDATE","EMPLOYEE_PROFILE",actor.id(),
            (id==null?"新增":"修改")+section.label+"，字段："+(changed.isEmpty()?"未更改":changed));
    }
    @Transactional
    public void deleteEntry(Section section,Long id,SessionUser actor) {
        if(jdbc.update("DELETE FROM "+section.table+" WHERE id=? AND employee_id=?",id,actor.id())!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或不属于您");
        logs.log(actor.id(),"PROFILE_ENTRY_DELETE","EMPLOYEE_PROFILE",actor.id(),"删除"+section.label+"记录");
    }
    private void validate(Object form) {
        var errors=validator.validate(form);
        if(!errors.isEmpty())throw new IllegalArgumentException(errors.iterator().next().getMessage());
    }
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
    private static String assignments(Map<String,Object> values){return values.keySet().stream().map(k->k+"=?").collect(Collectors.joining(","));}
    private static String s(Map<String,Object> row,String key){return ProfileForms.text(row.get(key));}
}
