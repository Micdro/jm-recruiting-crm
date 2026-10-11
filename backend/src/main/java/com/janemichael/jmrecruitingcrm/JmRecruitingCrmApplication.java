package com.janemichael.jmrecruitingcrm;

import com.janemichael.jmrecruitingcrm.common.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JmRecruitingCrmApplication {

	public static void main(String[] args) {
		DatabaseUrl.apply(System.getenv());
		SpringApplication.run(JmRecruitingCrmApplication.class, args);
	}

}
