package com.finalYear.smartClassRoom.service.impl;

import com.finalYear.smartClassRoom.dto.request.TeacherRequest;
import com.finalYear.smartClassRoom.dto.response.TeacherResponse;
import com.finalYear.smartClassRoom.entity.Department;
import com.finalYear.smartClassRoom.entity.Teacher;
import com.finalYear.smartClassRoom.entity.User;
import com.finalYear.smartClassRoom.exception.DuplicateResourceException;
import com.finalYear.smartClassRoom.exception.ResourceNotFoundException;
import com.finalYear.smartClassRoom.repository.AttendanceSessionRepository;
import com.finalYear.smartClassRoom.repository.DepartmentRepository;
import com.finalYear.smartClassRoom.repository.MarksRepository;
import com.finalYear.smartClassRoom.repository.TeacherAttendanceRepository;
import com.finalYear.smartClassRoom.repository.TeacherRepository;
import com.finalYear.smartClassRoom.repository.UserRepository;
import com.finalYear.smartClassRoom.service.CurrentUserContextService;
import com.finalYear.smartClassRoom.service.TeacherService;
import com.finalYear.smartClassRoom.service.UserDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeacherServiceImpl implements TeacherService {

    private final TeacherRepository           teacherRepository;
    private final UserRepository               userRepository;
    private final DepartmentRepository         departmentRepository;
    private final PasswordEncoder              passwordEncoder;
    private final CurrentUserContextService    currentUserCtx;
    private final AttendanceSessionRepository  attendanceSessionRepository;
    private final TeacherAttendanceRepository  teacherAttendanceRepository;
    private final MarksRepository              marksRepository;
    private final UserDeletionService          userDeletionService;

    @Override
    @Transactional
    public TeacherResponse createTeacher(TeacherRequest request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException(
                    "Email already exists : " + request.getEmail());
        }

        if (teacherRepository.existsByEmployeeId(request.getEmployeeId())) {
            throw new DuplicateResourceException(
                    "Employee ID already exists : " + request.getEmployeeId());
        }

        Department department = null;

        if (request.getDepartmentId() != null) {
            department = departmentRepository.findById(request.getDepartmentId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Department",
                                    request.getDepartmentId()));
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(User.Role.TEACHER)
                .enabled(true)
                .build();

        userRepository.save(user);

        Teacher teacher = Teacher.builder()
                .user(user)
                .employeeId(request.getEmployeeId())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .designation(request.getDesignation())
                .specialization(request.getSpecialization())
                .phone(request.getPhone())
                .department(department)
                .active(true)
                .build();

        teacherRepository.save(teacher);

        return mapToResponse(teacher);
    }

    @Override
    @Transactional
    public TeacherResponse updateTeacher(Long id, TeacherRequest request) {

        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Teacher", id));

        teacher.getUser().setFirstName(request.getFirstName());
        teacher.getUser().setLastName(request.getLastName());
        teacher.setDesignation(request.getDesignation());
        teacher.setSpecialization(request.getSpecialization());
        teacher.getUser().setPhone(request.getPhone());

        if (request.getDepartmentId() != null) {

            Department department = departmentRepository
                    .findById(request.getDepartmentId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Department",
                                    request.getDepartmentId()));

            teacher.setDepartment(department);
        }

        teacherRepository.save(teacher);

        return mapToResponse(teacher);
    }

    @Override
    public TeacherResponse getTeacherById(Long id) {

        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Teacher", id));

        return mapToResponse(teacher);
    }

    @Override
    public Page<TeacherResponse> getAllTeachers(Pageable pageable, Long departmentId) {
        // Resolve the effective dept for this caller (HOD/TEACHER always use own dept)
        Long effectiveDeptId = currentUserCtx.resolveEffectiveDepartmentId(departmentId);

        if (effectiveDeptId != null) {
            return teacherRepository
                    .findByDepartment_IdAndActiveTrue(effectiveDeptId, pageable)
                    .map(this::mapToResponse);
        }
        // null = SUPER_ADMIN/ADMIN with no filter — return all active teachers
        return teacherRepository.findByActiveTrue(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional
    public void deleteTeacher(Long id) {

        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Teacher", id));

        // Phase 2: Dept-level access check
        if (teacher.getDepartment() != null) {
            currentUserCtx.assertDepartmentAccess(teacher.getDepartment().getId());
        }

        // ── Cascade-delete all FK-dependent rows in correct order ────────────

        // 1. Marks: set teacher_id = NULL (marks are exam records — keep them, just unlink teacher)
        marksRepository.clearTeacherFromMarks(id);

        // 2. TeacherAttendance rows
        teacherAttendanceRepository.deleteAll(
                teacherAttendanceRepository.findByTeacher(teacher));
        teacherAttendanceRepository.flush();

        // 3. AttendanceSessions (their child Attendance rows cascade automatically)
        attendanceSessionRepository.deleteAll(
                attendanceSessionRepository.findByTeacher_Id(id));
        attendanceSessionRepository.flush();

        // 4. Timetables cascade from Teacher entity (cascade=ALL, orphanRemoval=true)
        teacher.getTimetables().clear();
        teacherRepository.saveAndFlush(teacher);

        // 5. Permanently remove the Teacher profile row
        User linkedUser = teacher.getUser();
        teacherRepository.delete(teacher);
        teacherRepository.flush();

        // 6. Delete the User + all its dependent records (RefreshToken, Notification, AuditLog, AISession, AIQuery)
        if (linkedUser != null) {
            userDeletionService.deleteUser(linkedUser);
        }

        log.info("[TeacherService] Permanently deleted teacher id={} (user={})",
                id, linkedUser != null ? linkedUser.getEmail() : "none");
    }

    private TeacherResponse mapToResponse(Teacher teacher) {

        return TeacherResponse.builder()
                .id(teacher.getId())
                .userId(teacher.getUser().getId())
                .email(teacher.getUser().getEmail())
                .employeeId(teacher.getEmployeeId())
                .firstName(teacher.getUser().getFirstName())
                .lastName(teacher.getUser().getLastName())
                .designation(teacher.getDesignation())
                .specialization(teacher.getSpecialization())
                .phone(teacher.getUser().getPhone())
                .departmentId(
                        teacher.getDepartment() != null
                                ? teacher.getDepartment().getId()
                                : null)
                .departmentName(
                        teacher.getDepartment() != null
                                ? teacher.getDepartment().getName()
                                : null)
                .profileImage(teacher.getProfileImage())
                .active(teacher.isActive())
                .createdAt(teacher.getCreatedAt())
                .build();
    }
}