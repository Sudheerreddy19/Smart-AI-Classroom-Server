package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    List<Attendance> findByStudent(Student student);

    List<Attendance> findByAttendanceSession(AttendanceSession attendanceSession);

    Optional<Attendance> findByAttendanceSessionAndStudent(
            AttendanceSession attendanceSession,
            Student student);

    List<Attendance> findByStatus(Attendance.AttendanceStatus status);

    long countByAttendanceSessionAndStatus(AttendanceSession session, Attendance.AttendanceStatus status);

}