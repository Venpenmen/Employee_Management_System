package com.example.ems.profile;

/** 本人联系方式与补充信息表单。只包含允许本人修改的字段；姓名、身份证、角色、部门、职位、工资等不在其中，额外提交的参数会被忽略。 */
public class BasicProfileForm {
    private Integer version;
    private String phone;
    private String email;
    private String address;
    private String emergencyName;
    private String emergencyRelation;
    private String emergencyPhone;
    private String gender;
    private String biography;

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getEmergencyName() { return emergencyName; }
    public void setEmergencyName(String emergencyName) { this.emergencyName = emergencyName; }
    public String getEmergencyRelation() { return emergencyRelation; }
    public void setEmergencyRelation(String emergencyRelation) { this.emergencyRelation = emergencyRelation; }
    public String getEmergencyPhone() { return emergencyPhone; }
    public void setEmergencyPhone(String emergencyPhone) { this.emergencyPhone = emergencyPhone; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
}
