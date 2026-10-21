package com.finalYear.smartClassRoom.repository;

import com.finalYear.smartClassRoom.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EnvironmentDataRepository extends JpaRepository<EnvironmentData, Long> {

    List<EnvironmentData> findByClassroom(Classroom classroom);

    List<EnvironmentData> findByClassroomOrderByRecordedAtAsc(Classroom classroom);

    EnvironmentData findTopByClassroomOrderByRecordedAtDesc(Classroom classroom);

    java.util.Optional<EnvironmentData> findTopByOrderByRecordedAtDesc();
}