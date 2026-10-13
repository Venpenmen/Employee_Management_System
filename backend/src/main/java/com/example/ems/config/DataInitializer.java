package com.example.ems.config;

import com.example.ems.service.AuthService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final AuthService authService;
    public DataInitializer(JdbcTemplate jdbc, AuthService authService) { this.jdbc=jdbc; this.authService=authService; }

    @Override @Transactional
    public void run(String... args) {
        Integer deptCount=jdbc.queryForObject("SELECT COUNT(*) FROM department",Integer.class);
        if(deptCount==0){
            jdbc.update("INSERT INTO department(department_name,base_salary,status,description) VALUES('A部门',5000,1,'产品与研发'),('B部门',4800,1,'市场与运营'),('C部门',4500,1,'行政与支持')");
            seedPositions("A部门",new String[]{"Aa","Ab"}); seedPositions("B部门",new String[]{"Ba","Bb","Bc"}); seedPositions("C部门",new String[]{"Ca","Cb"});
        }
        Integer employeeCount=jdbc.queryForObject("SELECT COUNT(*) FROM employee",Integer.class);
        if(employeeCount==0){
            Long a=dept("A部门"),b=dept("B部门"),c=dept("C部门");
            addUser("260001","系统高层","110101199001010011","1990-01-01",null,null,2,"2020-01-01","Admin123");
            Long managerA=addUser("260002","张经理","110101198805060012","1988-05-06",a,pos(a,"部门经理"),1,"2021-03-01","Manager123");
            Long managerB=addUser("260003","李经理","110101198703080013","1987-03-08",b,pos(b,"部门经理"),1,"2021-04-01","Manager123");
            Long managerC=addUser("260004","王经理","110101198910100014","1989-10-10",c,pos(c,"部门经理"),1,"2021-05-01","Manager123");
            addUser("260005","赵一","110101199605120015","1996-05-12",a,pos(a,"Aa"),0,"2023-07-01","Employee123");
            addUser("260006","钱二","110101199702140016","1997-02-14",a,pos(a,"Ab"),0,"2024-01-15","Employee123");
            addUser("260007","孙三","110101199409180017","1994-09-18",b,pos(b,"Ba"),0,"2022-08-01","Employee123");
            addUser("260008","周四","110101199811220018","1998-11-22",c,pos(c,"Ca"),0,"2025-02-10","Employee123");
            jdbc.update("UPDATE department SET manager_id=? WHERE id=?",managerA,a);
            jdbc.update("UPDATE department SET manager_id=? WHERE id=?",managerB,b);
            jdbc.update("UPDATE department SET manager_id=? WHERE id=?",managerC,c);
        }
        seedDictionary();
        seedAttendanceRule();
        repairLegacyPermissionData();
    }

    private void seedPositions(String name,String[] positions){
        Long id=dept(name);
        jdbc.update("INSERT INTO position_info(department_id,position_name,position_salary,is_manager_position) VALUES(?,'部门经理',3000,1)",id);
        for(int i=0;i<positions.length;i++) jdbc.update("INSERT INTO position_info(department_id,position_name,position_salary) VALUES(?,?,?)",id,positions[i],1200+i*500);
    }
    private Long addUser(String no,String name,String idNo,String birth,Long dept,Long position,int type,String hire,String password){
        jdbc.update("INSERT INTO employee(employee_no,name,identity_number,birth_date,phone,department_id,position_id,user_type,hire_date,employment_status) VALUES(?,?,?,?,?,?,?,?,?,1)",no,name,idNo,birth,"138"+no+"00",dept,position,type,hire);
        Long id=jdbc.queryForObject("SELECT id FROM employee WHERE employee_no=?",Long.class,no);
        jdbc.update("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)",id,authService.encode(password)); return id;
    }
    private Long dept(String name){return jdbc.queryForObject("SELECT id FROM department WHERE department_name=?",Long.class,name);}
    private Long pos(Long dept,String name){return jdbc.queryForObject("SELECT id FROM position_info WHERE department_id=? AND position_name=?",Long.class,dept,name);}
    private void seedDictionary(){
        String[][] values={{"gender","0","女"},{"gender","1","男"},{"marriage","0","未婚"},{"marriage","1","已婚"},{"political","0","群众"},{"political","1","共青团员"},{"political","2","中共党员"},
                {"nation","HAN","汉族"},{"nation","MONGOL","蒙古族"},{"nation","HUI","回族"},{"nation","TIBETAN","藏族"},{"nation","UYGHUR","维吾尔族"},{"nation","MIAO","苗族"},{"nation","YI","彝族"},{"nation","ZHUANG","壮族"},{"nation","BUYI","布依族"},{"nation","KOREAN","朝鲜族"},{"nation","MANCHU","满族"},
                {"leave_type","ANNUAL","年假"},{"leave_type","SICK","病假"},{"leave_type","PERSONAL","事假"},{"leave_type","MARRIAGE","婚假"},{"leave_type","MATERNITY","产假/陪产假"},{"leave_type","BEREAVEMENT","丧假"},{"leave_type","COMPENSATORY","调休"},{"leave_type","OTHER","其他"}};
        int i=0; for(String[] v:values) jdbc.update("INSERT IGNORE INTO dictionary_item(dict_type,item_value,item_label,sort_order) VALUES(?,?,?,?)",v[0],v[1],v[2],i++);
    }

    private void seedAttendanceRule(){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM attendance_rule",Integer.class);
        if(count!=null&&count==0) jdbc.update("INSERT INTO attendance_rule(rule_name,work_start_time,work_end_time,late_grace_minutes,early_grace_minutes,status) VALUES('默认工作制','09:00:00','18:00:00',5,0,1)");
    }

    /** 清理旧版本中普通员工被错误分配“部门经理”职位的数据。 */
    private void repairLegacyPermissionData(){
        jdbc.update("""
            UPDATE employee e
            JOIN position_info p ON p.id=e.position_id
            SET e.position_id=NULL
            WHERE e.user_type=0 AND p.is_manager_position=1
            """);
    }
}
