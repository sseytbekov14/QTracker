package com.kpmg.qtracker.config;

import com.kpmg.qtracker.service.SystemAdmins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Reads the System Admin Team (SystemAdmins) from app.system-admins (SYSTEM_ADMIN_EMAILS in the server's .env) when the
 * application starts. The log names how many, never the addresses.
 */
@Configuration
@Slf4j
public class SystemAdminsConfig {

    public SystemAdminsConfig(@Value("${app.system-admins:}") String configured) {
        SystemAdmins.replace(configured);
        if (SystemAdmins.size() > 0) {
            log.info("System Admin Team: {} account(s) set on the server (read only everywhere)", SystemAdmins.size());
        }
    }
}
