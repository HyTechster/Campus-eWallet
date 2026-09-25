package com.hytechster.ewallet.user;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hytechster.ewallet.common.BusinessException;
import com.hytechster.ewallet.common.NotFoundException;
import com.hytechster.ewallet.ledger.LedgerService;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LedgerService ledger;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, LedgerService ledger) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.ledger = ledger;
    }

    /** Student sign-up. The wallet is opened in the same transaction. */
    @Transactional
    public User registerStudent(RegistrationForm form) {
        return createUser(form.email(), form.password(), form.fullName(), form.studentId(), Role.USER);
    }

    /** Creates a user and, for students and merchants, their one wallet. */
    @Transactional
    public User createUser(String email, String rawPassword, String fullName, String studentId, Role role) {
        String normalizedEmail = email.trim().toLowerCase();
        if (users.existsByEmail(normalizedEmail)) {
            throw new BusinessException("An account with this email already exists.");
        }
        if (studentId != null && !studentId.isBlank() && users.existsByStudentId(studentId.trim().toUpperCase())) {
            throw new BusinessException("An account with this student ID already exists.");
        }
        User user = users.save(new User(normalizedEmail, passwordEncoder.encode(rawPassword), fullName, studentId, role));
        if (role != Role.ADMIN) {
            ledger.openWallet(user);
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User get(long userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Finds a person by email or student ID, case-insensitive. */
    @Transactional(readOnly = true)
    public Optional<User> findByEmailOrStudentId(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String q = query.trim();
        return q.contains("@")
                ? users.findByEmail(q.toLowerCase())
                : users.findByStudentId(q.toUpperCase());
    }

    @Transactional
    public void setFrozen(long userId, boolean frozen) {
        User user = get(userId);
        if (user.getRole() == Role.ADMIN) {
            throw new BusinessException("Admin accounts can't be frozen.");
        }
        user.setStatus(frozen ? UserStatus.FROZEN : UserStatus.ACTIVE);
    }
}
