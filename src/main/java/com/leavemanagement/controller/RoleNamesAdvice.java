package com.leavemanagement.controller;

import com.leavemanagement.config.RoleNames;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.util.Map;

/**
 * Makes the role names available to every page (the layout passes them on to the page scripts)
 */
@ControllerAdvice(assignableTypes = HomeController.class)
@RequiredArgsConstructor
public class RoleNamesAdvice {
    private final RoleNames roleNames;

    @ModelAttribute("roleNames")
    public Map<String, String> roleNames() {
        return roleNames.asMap();
    }
}
