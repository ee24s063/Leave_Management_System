package com.leavemanagement.config;

import com.leavemanagement.entity.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The names users see for each role. The role codes are fixed; the Lab Head's name comes from
 * app.role-names.lab-head, so it can be renamed without changing code.
 */
@Component
public class RoleNames {
    private final Map<Role, String> names = new EnumMap<>(Role.class);

    public RoleNames(@Value("${app.role-names.lab-head:Lab Head}") String labHead) {
        names.put(Role.EMPLOYEE, "Employee");
        names.put(Role.TEAM_LEAD, "Team Lead");
        names.put(Role.HR_ADMIN, "HR");
        names.put(Role.LAB_HEAD, labHead);
    }

    public String of(Role role) {
        return role == null ? "" : names.get(role);
    }

    public String labHead() {
        return names.get(Role.LAB_HEAD);
    }

    /**
     * Role code to name, for pages and scripts
     */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        names.forEach((role, name) -> map.put(role.name(), name));
        return map;
    }
}
