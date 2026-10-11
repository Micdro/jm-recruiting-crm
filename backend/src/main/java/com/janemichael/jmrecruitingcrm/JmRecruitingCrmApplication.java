package com.janemichael.jmrecruitingcrm;

import com.janemichael.jmrecruitingcrm.common.DatabaseUrl;
import com.janemichael.jmrecruitingcrm.desktop.DesktopLauncher;
import com.janemichael.jmrecruitingcrm.desktop.DesktopStartupError;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JmRecruitingCrmApplication {

	public static void main(String[] args) {
		if (DesktopLauncher.requested()) {
			try {
				DesktopLauncher.run(args);
			} catch (Throwable problem) {
				DesktopStartupError.report(problem);
				System.exit(1);
			}
			return;
		}
		DatabaseUrl.apply(System.getenv());
		SpringApplication.run(JmRecruitingCrmApplication.class, args);
	}

}
