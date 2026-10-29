package com.example.ems.upgrade;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;import java.nio.file.*;
public class GenerateRecruitmentWorkbook {
 public static void main(String[] args)throws Exception{
  try(var book=new XSSFWorkbook()){
   var sheet=book.createSheet("虚构招聘");
   String[][] rows={{"姓名","身份证","出生日期","入职日期","部门","职位"},{"BatchSyntheticA","110101199501019911","1995-01-01","2020-01-01","测试研发部","测试工程师"},{"BatchDuplicate","110101199501019911","1995-01-01","2020-01-01","测试研发部","测试工程师"},{"BatchInvalid","wrong-id","1995-01-01","2020-01-01","测试研发部","测试工程师"}};
   for(int i=0;i<rows.length;i++){var row=sheet.createRow(i);for(int j=0;j<rows[i].length;j++)row.createCell(j).setCellValue(rows[i][j]);}
   try(var out=Files.newOutputStream(Path.of(args[0]),StandardOpenOption.CREATE_NEW)){book.write(out);}
  }
 }
}
