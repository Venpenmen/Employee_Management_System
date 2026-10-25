package com.example.ems.profile;

/** 教育经历、工作经历、技能、证书共用的明细表单；不同分区只使用各自字段。日期以字符串接收，校验失败时可原样回显输入。 */
public class ProfileEntryForm {
    private String school;
    private String degree;
    private String major;
    private String startMonth;
    private String endMonth;
    private boolean currentEntry;
    private String company;
    private String department;
    private String position;
    private String description;
    private String name;
    private String proficiency;
    private String notes;
    private String issuer;
    private String certificateNumber;
    private String obtainedDate;
    private String expiryDate;

    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public String getMajor() { return major; }
    public void setMajor(String major) { this.major = major; }
    public String getStartMonth() { return startMonth; }
    public void setStartMonth(String startMonth) { this.startMonth = startMonth; }
    public String getEndMonth() { return endMonth; }
    public void setEndMonth(String endMonth) { this.endMonth = endMonth; }
    public boolean isCurrentEntry() { return currentEntry; }
    public void setCurrentEntry(boolean currentEntry) { this.currentEntry = currentEntry; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProficiency() { return proficiency; }
    public void setProficiency(String proficiency) { this.proficiency = proficiency; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String certificateNumber) { this.certificateNumber = certificateNumber; }
    public String getObtainedDate() { return obtainedDate; }
    public void setObtainedDate(String obtainedDate) { this.obtainedDate = obtainedDate; }
    public String getExpiryDate() { return expiryDate; }
    public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }
}
