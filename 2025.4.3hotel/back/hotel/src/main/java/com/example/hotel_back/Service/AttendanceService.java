package com.example.hotel_back.Service;

import com.example.hotel_back.Common.result.PageResult;
import com.example.hotel_back.Pojo.DAO.AttendanceQueryDTO;
import com.example.hotel_back.Pojo.DAO.AttendanceStatusUpdateDTO;
import com.example.hotel_back.Pojo.DAO.employeedao.EAttendanceQueryDTO;
import com.example.hotel_back.Pojo.VO.employeevo.AttendanceStatusVO;

import java.time.LocalDate;

public interface AttendanceService {
    /**
     * 员工签到
     * @param employeeId 员工ID
     */
    void checkIn(Long employeeId);
    /**
     * 员工签退
     * @param employeeId 员工ID
     */
    void checkOut(Long employeeId);
    /**
     * 分页查询考勤记录
     * @param attendanceQueryDTO 查询条件
     * @return 分页结果
     */
    PageResult pageQuery(AttendanceQueryDTO attendanceQueryDTO);
    /**
     * 分页查询考勤记录
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    PageResult pageQuery(EAttendanceQueryDTO queryDTO);
    /**
     * 修改考勤状态
     * @param updateDTO 更新信息
     */
    void updateStatus(AttendanceStatusUpdateDTO updateDTO);
    /**
     * 获取员工当日签到状态
     * @param employeeId 员工ID
     * @param date 日期
     * @return 签到状态和打卡时间，未找到记录时状态为 -1
     */
    AttendanceStatusVO getAttendanceStatus(Long employeeId, LocalDate date);
}
